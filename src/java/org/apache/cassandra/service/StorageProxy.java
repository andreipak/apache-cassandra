/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.collect.Multimap;
import org.apache.log4j.Logger;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.builder.ToStringBuilder;

import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Bounds;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.net.IAsyncResult;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.LatencyTracker;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.WrappedRunnable;


public class StorageProxy implements StorageProxyMBean
{


    private static final Logger logger = Logger.getLogger(StorageProxy.class);

    // mbean stuff
    private static final LatencyTracker readStats = new LatencyTracker();
    private static final LatencyTracker rangeStats = new LatencyTracker();
    private static final LatencyTracker writeStats = new LatencyTracker();
    private static final LatencyTracker hintStats = new LatencyTracker();
    private static AtomicLong laggedHints = new AtomicLong();
    private static AtomicLong weakParLocal = new AtomicLong();
    private static AtomicLong weakParRemote = new AtomicLong();
    private static AtomicLong weakParConsistencyAll = new AtomicLong();
    private static AtomicLong weakParConsistencyUnder = new AtomicLong();
    private static AtomicLong recentReadRepairs = new AtomicLong();
    
    private static boolean hintedHandoffEnabled = DatabaseDescriptor.hintedHandoffEnabled();
    /**
     * wait additionally for this number of millis for all endpoint responses.
     * (if during this additional timeout some endpoint did not responded - 
     *  mutation will be added to hinted handoff for all not responded in time nodes) 
     *  
     *  0 -  turn it off
     */
    private static int     hintedHandoffWriteLatencyThreshold = 1;

    private StorageProxy() {}
    static
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(new StorageProxy(), new ObjectName("org.apache.cassandra.service:type=StorageProxy"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static final Comparator<String> keyComparator = new Comparator<String>()
    {
        public int compare(String o1, String o2)
        {
            IPartitioner<?> p = StorageService.getPartitioner();
            return p.decorateKey(o1).compareTo(p.decorateKey(o2));
        }
    };

    /**
     * Use this method to have these RowMutations applied
     * across all replicas. This method will take care
     * of the possibility of a replica being down and hint
     * the data across to some other replica.
     *
     * This is the ZERO consistency level. We do not wait for replies.
     *
     * @param mutations the mutations to be applied across the replicas
    */
    public static void mutate(List<RowMutation> mutations)
    {
        long startTime = System.nanoTime();
        try
        {
            StorageService ss = StorageService.instance;
            for (final RowMutation rm: mutations)
            {
                try
                {
                    String table = rm.getTable();
                    AbstractReplicationStrategy rs = ss.getReplicationStrategy(table);

                    List<InetAddress> naturalEndpoints = ss.getNaturalEndpoints(table, rm.key());
                    Multimap<InetAddress,InetAddress> hintedEndpoints = rs.getHintedEndpoints(table, naturalEndpoints);
                    Message unhintedMessage = null; // lazy initialize for non-local, unhinted writes

                    // 3 cases:
                    // 1. local, unhinted write: run directly on write stage
                    // 2. non-local, unhinted write: send row mutation message
                    // 3. hinted write: add hint header, and send message
                    for (Map.Entry<InetAddress, Collection<InetAddress>> entry : hintedEndpoints.asMap().entrySet())
                    {
                        InetAddress destination = entry.getKey();
                        Collection<InetAddress> targets = entry.getValue();
                        if (targets.size() == 1 && targets.iterator().next().equals(destination))
                        {
                            // unhinted writes
                            if (destination.equals(FBUtilities.getLocalAddress()))
                            {
                                if (logger.isDebugEnabled())
                                    logger.debug("insert writing local key " + rm.key());
                                Runnable runnable = new WrappedRunnable()
                                {
                                    public void runMayThrow() throws IOException
                                    {
                                        rm.apply();
                                    }
                                };
                                StageManager.getStage(StageManager.MUTATION_STAGE).execute(runnable);
                            }
                            else
                            {
                                if (unhintedMessage == null)
                                    unhintedMessage = rm.makeRowMutationMessage();
                                if (logger.isDebugEnabled())
                                    logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + destination);
                                MessagingService.instance.sendOneWay(unhintedMessage, destination);
                            }
                        }
                        else
                        {
                            Message hintedMessage = addHintHeader(
                                    rm,
                                    destination,
                                    targets);
                            MessagingService.instance.sendOneWay(hintedMessage, destination);
                        }
                    }
                }
                catch (IOException e)
                {
                    throw new RuntimeException("error inserting key " + rm.key(), e);
                }
            }
        }
        finally
        {
            writeStats.addNano(System.nanoTime() - startTime);
        }
    }

    private static void addHintHeader(Message message, InetAddress target)
    {
        byte[] oldHint = message.getHeader(RowMutation.HINT);
        byte[] hint = oldHint == null ? target.getAddress() : ArrayUtils.addAll(oldHint, target.getAddress());
        message.setHeader(RowMutation.HINT, hint);
    }

    public static void mutateBlocking(List<RowMutation> mutations, ConsistencyLevel consistency_level) throws UnavailableException, TimeoutException
    {
        long startTime = System.nanoTime();
        ArrayList<WriteResponseHandler> responseHandlers = new ArrayList<WriteResponseHandler>(mutations.size());

        RowMutation mostRecentRowMutation = null;
        StorageService ss = StorageService.instance;
        try
        {
            for (RowMutation rm : mutations)
            {
                mostRecentRowMutation = rm;
                String table = rm.getTable();
                AbstractReplicationStrategy rs = ss.getReplicationStrategy(table);

                List<InetAddress> naturalEndpoints = ss.getNaturalEndpoints(table, rm.key());
                Collection<InetAddress> writeEndpoints = rs.getWriteEndpoints(StorageService.getPartitioner().getToken(rm.key()), table, naturalEndpoints);
                Multimap<InetAddress, InetAddress> hintedEndpoints = rs.getHintedEndpoints(table, writeEndpoints);
                int blockFor = determineBlockFor(writeEndpoints.size(), consistency_level);

                // avoid starting a write we know can't achieve the required consistency
                assureSufficientLiveNodes(blockFor, writeEndpoints, hintedEndpoints, consistency_level);
                
                // send out the writes, as in mutate() above, but this time with a callback that tracks responses
                int destinationCount = hintedEndpoints.keySet().size();
                final WriteResponseHandler responseHandler = ss.getWriteResponseHandler(blockFor,destinationCount, consistency_level, table);
                responseHandlers.add(responseHandler);

                for (Map.Entry<InetAddress, Collection<InetAddress>> entry : hintedEndpoints.asMap().entrySet())
                {
                    InetAddress destination = entry.getKey();
                    Collection<InetAddress> targets = entry.getValue();

                    if (targets.size() == 1 && targets.iterator().next().equals(destination))
                    {
                        // unhinted writes
                        if (destination.equals(FBUtilities.getLocalAddress()))
                        {
                            insertLocalMessage(rm, responseHandler);
                        }
                        else
                        {
                            // belongs on a different server.  send it there.
                            responseHandler.addEndpoint(destination);
                            Message unhintedMessage = rm.makeRowMutationMessage();
                            if (logger.isDebugEnabled())
                                logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + destination);
                            MessagingService.instance.sendRR(unhintedMessage, destination, responseHandler);
                        }
                    }
                    else
                    {
                        // hinted
                        // (non-destination hints are part of the callback and count towards consistency only under CL.ANY)
                        if (writeEndpoints.contains(destination) || consistency_level == ConsistencyLevel.ANY)
                        {
                            if (destination.equals(FBUtilities.getLocalAddress()))
                            {
                                insertLocalMessage(rm, responseHandler);
                                
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream( bos );
                                RowMutation.serializer().serialize(rm, dos);
                                byte[] bytes = bos.toByteArray();
                                
                                for (InetAddress target : targets)
                                {
                                    if (!target.equals(destination))
                                    {
                                        HintedHandOffManager.instance().storeHint(target, rm, bytes);
                                    }
                                }
                                
                            } else
                            {
                                Message hintedMessage = addHintHeader(
                                        rm,
                                        destination,
                                        targets);
                                responseHandler.addEndpoint(destination);
                                
                                MessagingService.instance.sendRR(hintedMessage, destination, responseHandler);
                            }
                        }
                        else
                        {
                            if (destination.equals(FBUtilities.getLocalAddress()))
                            {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                DataOutputStream dos = new DataOutputStream( bos );
                                RowMutation.serializer().serialize(rm, dos);
                                byte[] bytes = bos.toByteArray();

                                for (InetAddress target : targets)
                                {
                                    if (!target.equals(destination))
                                    {
                                        HintedHandOffManager.instance().storeHint(target, rm, bytes);
                                    }
                                }

                            } else
                            {
                                Message hintedMessage = addHintHeader(
                                        rm,
                                        destination,
                                        targets);

                                MessagingService.instance.sendOneWay(hintedMessage, destination);
                            }
                        }
                    }
                }
            }
            
            // wait for writes.  throws timeoutexception if necessary
            for( WriteResponseHandler responseHandler : responseHandlers )
            {
                responseHandler.get();
            }

            // now wait a bit more
            if (hintedHandoffWriteLatencyThreshold>0)
            {
                long hStart = System.nanoTime();
                for( int i=responseHandlers.size();i-->0; )
                {
                    WriteResponseHandler responseHandler = responseHandlers.get(i);
                    
                    if (!responseHandler.getAllResponses(hintedHandoffWriteLatencyThreshold))
                    {
                        List<InetAddress> laggingEndpoints = responseHandler.getLaggingEndpoints();
                        if (laggingEndpoints==null || laggingEndpoints.size()==0)
                        {
                            continue;
                        }
                        // mutations and response handlers will have the same index.
                        RowMutation rm = mutations.get(i);
                        
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        DataOutputStream dos = new DataOutputStream( bos );
                        RowMutation.serializer().serialize(rm, dos);
                        byte[] bytes = bos.toByteArray();

                        for (InetAddress inetAddress : laggingEndpoints) 
                        {
                            HintedHandOffManager.instance().storeHint(inetAddress, rm, bytes);
                            laggedHints.incrementAndGet();
                        }
                    }
                }
                hintStats.addNano( System.nanoTime()-hStart );
            }
            
        }
        catch (IOException e)
        {
            if (mostRecentRowMutation == null)
                throw new RuntimeException("no mutations were seen but found an error during write anyway", e);
            else
                throw new RuntimeException("error writing key " + mostRecentRowMutation.key(), e);
        }
        finally
        {
            writeStats.addNano(System.nanoTime() - startTime);
        }

    }

    private static Message addHintHeader(RowMutation rm, InetAddress destination,
            Collection<InetAddress> targets) throws IOException
    {
        Message hintedMessage = rm.makeRowMutationMessage();
        for (InetAddress target : targets)
        {
            if (!target.equals(destination))
            {
                addHintHeader(hintedMessage, target);
                if (logger.isDebugEnabled())
                    logger.debug("insert writing key " + rm.key() + " to " + hintedMessage.getMessageId() + "@" + destination + " for " + target);
            }
        }
        return hintedMessage;
    }

    private static void assureSufficientLiveNodes(int blockFor, Collection<InetAddress> writeEndpoints, Multimap<InetAddress, InetAddress> hintedEndpoints, ConsistencyLevel consistencyLevel)
            throws UnavailableException
    {
        if (consistencyLevel == ConsistencyLevel.ANY)
        {
            // ensure there are blockFor distinct living nodes (hints are ok).
            if (hintedEndpoints.keySet().size() < blockFor)
                throw new UnavailableException();
        }

        // count destinations that are part of the desired target set
        int liveNodes = 0;
        for (InetAddress destination : hintedEndpoints.keySet())
        {
            if (writeEndpoints.contains(destination))
                liveNodes++;
        }
        if (liveNodes < blockFor)
        {
            throw new UnavailableException();
        }
    }

    private static void insertLocalMessage(final RowMutation rm, final WriteResponseHandler responseHandler)
    {
        if (logger.isDebugEnabled())
            logger.debug("insert writing local key " + rm.key());
        Runnable runnable = new WrappedRunnable()
        {
            public void runMayThrow() throws IOException
            {
                rm.apply();
                responseHandler.localResponse();
            }
        };
        StageManager.getStage(StageManager.MUTATION_STAGE).execute(runnable);
    }

    private static int determineBlockFor(int expandedTargets, ConsistencyLevel consistency_level)
    {
        switch (consistency_level)
        {
            case ONE:
            case ANY:
                return 1;
            case QUORUM:
                return (expandedTargets / 2) + 1;
            case DCQUORUM:
            case DCQUORUMSYNC:
                // TODO this is broken
                return expandedTargets;
            case ALL:
                return expandedTargets;
            default:
                throw new UnsupportedOperationException("invalid consistency level " + consistency_level);
        }
    }

    /**
     * Performs the actual reading of a row out of the StorageService, fetching
     * a specific set of column names from a given column family.
     */
    public static List<Row> readProtocol(List<ReadCommand> commands, ConsistencyLevel consistency_level)
            throws IOException, UnavailableException, TimeoutException, InvalidRequestException
    {
        if (StorageService.instance.isBootstrapMode())
            throw new UnavailableException();
        long startTime = System.nanoTime();

        List<Row> rows;
        if (consistency_level == ConsistencyLevel.ONE)
        {
            rows = DatabaseDescriptor.getParallelWeakRead() ?  weakReadParallel(commands) : weakRead(commands);
        }
        else
        {
            assert consistency_level.getValue() >= ConsistencyLevel.QUORUM.getValue();
            rows = strongRead(commands, consistency_level);
        }

        readStats.addNano(System.nanoTime() - startTime);
        return rows;
    }

    private static List<Row> weakRead(List<ReadCommand> commands) throws IOException, UnavailableException, TimeoutException
    {
        List<Row> rows = new ArrayList<Row>();

        // send off all the commands asynchronously
        List<Future<Object>> localFutures = null;
        HashMap<ReadCommand, IAsyncResult> remoteResults = null;
        for (ReadCommand command: commands)
        {
            InetAddress endPoint = StorageService.instance.findSuitableEndPoint(command.table, command.key);
            if (endPoint.equals(FBUtilities.getLocalAddress()))
            {
                if (logger.isDebugEnabled())
                    logger.debug("weakread reading " + command + " locally");

                if (localFutures == null)
                    localFutures = new ArrayList<Future<Object>>();
                Callable<Object> callable = new weakReadLocalCallable(command);
                localFutures.add(StageManager.getStage(StageManager.READ_STAGE).submit(callable));
            }
            else
            {
                if (remoteResults == null)
                    remoteResults = new HashMap<ReadCommand, IAsyncResult>();
                Message message = command.makeReadMessage();
                if (logger.isDebugEnabled())
                    logger.debug("weakread reading " + command + " from " + message.getMessageId() + "@" + endPoint);
                remoteResults.put(command, MessagingService.instance.sendRR(message, endPoint));
            }
        }

        // wait for results
        if (localFutures != null)
        {
            for (Future<Object> future : localFutures)
            {
                Row row;
                try
                {
                    row = (Row) future.get();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                rows.add(row);
            }
        }
        if (remoteResults != null)
        {
            for (Map.Entry<ReadCommand, IAsyncResult> entry : remoteResults.entrySet())
            {
                IAsyncResult iar = entry.getValue();
                byte[] body;
                body = iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
                ByteArrayInputStream bufIn = new ByteArrayInputStream(body);
                ReadResponse response = ReadResponse.serializer().deserialize(new DataInputStream(bufIn));
                assert response.row() != null;
                rows.add(response.row());
                StorageService.instance.doConsistencyCheck(response.row(), entry.getKey(), iar.getFrom());
            }
        }

        return rows;
    }

    private static List<Row> weakReadParallel(List<ReadCommand> commands) throws IOException, UnavailableException, TimeoutException
    {
        List<ParallelWeakResponseHandler> parResponseHandlers = new ArrayList<ParallelWeakResponseHandler>(commands.size());
        List<List<InetAddress>> commandEndPoints = new ArrayList<List<InetAddress>>();
        List<Row> rows = new ArrayList<Row>();

        // send out read requests
        for (ReadCommand command: commands)
        {
            assert !command.isDigestQuery();
            
            final String table = command.table;
            final String key = command.key;
            int responseCount = 1;
            
            List<InetAddress> endpointList = StorageService.instance.findSuitableEndPoints(table, key);
            
            if (endpointList.size() < responseCount)
                throw new UnavailableException();

            ReadResponseResolver resolver = new ReadResponseResolver(table, key, responseCount);
            ParallelWeakResponseHandler parResponseHandler = new ParallelWeakResponseHandler(endpointList.size(), resolver);
            // data-request message is sent to all endpoints, the node that will actually get
            // the data for us. The other replicas are only sent a digest query.
            for (InetAddress endpoint : endpointList)
            {
                if (endpoint.equals(FBUtilities.getLocalAddress()))
                {
                    // short cutting for read local to bypass message ser/deser
                    if (logger.isDebugEnabled())
                        logger.debug("weakreadPar reading " + command + " locally");

                    Callable<Object> callable = new WeakReadParallelLocalCallable(command,parResponseHandler);
                    StageManager.getStage(StageManager.READ_STAGE).submit(callable);
                } else
                {
                    
                    Message m = command.makeReadMessage();
                    if (logger.isDebugEnabled())
                        logger.debug("weakreadPar reading data for " + command + " from " + m.getMessageId() + "@" + endpoint);
                    MessagingService.instance.sendRR(m, endpoint, parResponseHandler);
                }
            }
            parResponseHandlers.add(parResponseHandler);
            commandEndPoints.add(endpointList);
        }

        // read results and make a second pass for any digest mismatches
        for (ParallelWeakResponseHandler parResponseHandler : parResponseHandlers)
        {
            Row row = parResponseHandler.get();

            if (row != null)
                rows.add(row);

            // increment stats
            if (parResponseHandler.isServedFromLocal())
            {
                weakParLocal.incrementAndGet();
            } else
            {
                weakParRemote.incrementAndGet();
            }
            
            // submit the rest of work to consistency pool
            StorageService.instance.doConsistencyCheck(parResponseHandler);

        }


        return rows;
    }

    /*
     * This function executes the read protocol.
        // 1. Get the N nodes from storage service where the data needs to be
        // replicated
        // 2. Construct a message for read\write
         * 3. Set one of the messages to get the data and the rest to get the digest
        // 4. SendRR ( to all the nodes above )
        // 5. Wait for a response from at least X nodes where X <= N and the data node
         * 6. If the digest matches return the data.
         * 7. else carry out read repair by getting data from all the nodes.
        // 5. return success
     */
    private static List<Row> strongRead(List<ReadCommand> commands, ConsistencyLevel consistency_level) throws IOException, UnavailableException, TimeoutException
    {
        List<QuorumResponseHandler<Row>> quorumResponseHandlers = new ArrayList<QuorumResponseHandler<Row>>();
        List<List<InetAddress>> commandEndPoints = new ArrayList<List<InetAddress>>();
        List<Row> rows = new ArrayList<Row>();

        // send out read requests
        for (ReadCommand command: commands)
        {
            assert !command.isDigestQuery();
            ReadCommand digestCommand = command.copy();
            digestCommand.setDigestQuery(true);

            InetAddress dataPoint = StorageService.instance.findSuitableEndPoint(command.table, command.key);
            List<InetAddress> endpointList = StorageService.instance.getLiveNaturalEndpoints(command.table, command.key);
            final String table = command.table;
            int responseCount = determineBlockFor(DatabaseDescriptor.getReplicationFactor(table), consistency_level);
            if (endpointList.size() < responseCount)
                throw new UnavailableException();

            ReadResponseResolver resolver = new ReadResponseResolver(command.table, command.key, responseCount);
            QuorumResponseHandler<Row> quorumResponseHandler = new QuorumResponseHandler<Row>(responseCount, resolver);
            // data-request message is sent to dataPoint, the node that will actually get
            // the data for us. The other replicas are only sent a digest query.
            for (InetAddress endpoint : endpointList)
            {
                Message m = endpoint.equals(dataPoint) ? command.makeReadMessage() : digestCommand.makeReadMessage();
                if (logger.isDebugEnabled())
                    logger.debug("strongread reading " + (endpoint.equals(dataPoint) ? "data" : "digest") + " for " + command + " from " + m.getMessageId() + "@" + endpoint);
                MessagingService.instance.sendRR(m, endpoint, quorumResponseHandler);
            }
            quorumResponseHandlers.add(quorumResponseHandler);
            commandEndPoints.add(endpointList);
        }

        // read results and make a second pass for any digest mismatches
        List<QuorumResponseHandler<Row>> repairResponseHandlers = null;
        for (int i = 0; i < commands.size(); i++)
        {
            QuorumResponseHandler<Row> quorumResponseHandler = quorumResponseHandlers.get(i);
            Row row;
            ReadCommand command = commands.get(i);
            try
            {
                long startTime2 = System.currentTimeMillis();
                row = quorumResponseHandler.get();
                if (row != null)
                    rows.add(row);

                if (logger.isDebugEnabled())
                    logger.debug("quorumResponseHandler: " + (System.currentTimeMillis() - startTime2) + " ms.");
            }
            catch (DigestMismatchException ex)
            {
// do repair on strong reads regardless of doConsistenyChecks flag                
//                if (DatabaseDescriptor.getConsistencyCheck())
//                {
                    if (logger.isDebugEnabled())
                        logger.debug("Digest mismatch:", ex);
                    int responseCount = determineBlockFor(DatabaseDescriptor.getReplicationFactor(command.table), consistency_level);
                    ReadResponseResolver resolver = new ReadResponseResolver(command.table, command.key, responseCount);
                    QuorumResponseHandler<Row> qrhRepair = new QuorumResponseHandler<Row>(responseCount, resolver);
                    for (InetAddress endPoint : commandEndPoints.get(i))
                    {
                        Message messageRepair = command.makeReadMessage();
                        MessagingService.instance.sendRR(messageRepair, endPoint, qrhRepair);
                    }
                    if (repairResponseHandlers == null)
                        repairResponseHandlers = new ArrayList<QuorumResponseHandler<Row>>();
                    repairResponseHandlers.add(qrhRepair);
//                }
            }
        }

        // read the results for the digest mismatch retries
        if (repairResponseHandlers != null)
        {
            for (QuorumResponseHandler<Row> handler : repairResponseHandlers)
            {
                try
                {
                    Row row = handler.get();
                    if (row != null)
                        rows.add(row);
                }
                catch (DigestMismatchException e)
                {
                    throw new AssertionError(e); // full data requested from each node here, no digests should be sent
                }
            }
        }

        return rows;
    }

    /*
    * This function executes the read protocol locally.  Consistency checks are performed in the background.
    */

    public static List<Row> getRangeSlice(RangeSliceCommand command, ConsistencyLevel consistency_level)
    throws IOException, UnavailableException, TimeoutException
    {
        if (logger.isDebugEnabled())
            logger.debug(command);
        long startTime = System.nanoTime();

        final String table = command.keyspace;
        int responseCount = determineBlockFor(DatabaseDescriptor.getReplicationFactor(table), consistency_level);

        List<AbstractBounds> ranges = getRestrictedRanges(command.range);

        // now scan until we have enough results
        List<Row> rows = new ArrayList<Row>(command.max_keys);
        for (AbstractBounds range : ranges)
        {
            List<InetAddress> liveEndpoints = StorageService.instance.getLiveNaturalEndpoints(command.keyspace, range.right);
            if (liveEndpoints.size() < responseCount)
                throw new UnavailableException();
            DatabaseDescriptor.getEndPointSnitch(command.keyspace).sortByProximity(FBUtilities.getLocalAddress(), liveEndpoints);
            List<InetAddress> endpoints = liveEndpoints.subList(0, responseCount);

            RangeSliceCommand c2 = new RangeSliceCommand(command.keyspace, command.column_family, command.super_column, command.predicate, range, command.max_keys);

            // collect replies and resolve according to consistency level
            RangeSliceResponseResolver resolver = new RangeSliceResponseResolver(command.keyspace, endpoints, StorageService.getPartitioner());
            QuorumResponseHandler<List<Row>> handler = new QuorumResponseHandler<List<Row>>(responseCount, resolver);

            for (InetAddress endpoint : endpoints)
            {
                Message message = c2.getMessage();
                MessagingService.instance.sendRR(message, endpoint, handler);
                if (logger.isDebugEnabled())
                    logger.debug("reading " + c2 + " from " + message.getMessageId() + "@" + endpoint);
            }
            // TODO read repair on remaining replicas?

            // if we're done, great, otherwise, move to the next range
            try
            {
                if (logger.isDebugEnabled())
                {
                    for (Row row : handler.get())
                    {
                        logger.debug("range slices read " + row.key);
                    }
                }
                rows.addAll(handler.get());
            }
            catch (DigestMismatchException e)
            {
                throw new AssertionError(e); // no digests in range slices yet
            }
            if (rows.size() >= command.max_keys)
                break;
        }

        rangeStats.addNano(System.nanoTime() - startTime);
        return rows.size() > command.max_keys ? rows.subList(0, command.max_keys) : rows;
    }

    /**
     * Compute all ranges we're going to query, in sorted order. Nodes can be replica destinations for many ranges,
     * so we need to restrict each scan to the specific range we want, or else we'd get duplicate results.
     */
    static List<AbstractBounds> getRestrictedRanges(final AbstractBounds queryRange)
    {
        // special case for bounds containing exactly 1 (non-minimum) token
        if (queryRange instanceof Bounds && queryRange.left.equals(queryRange.right) && !queryRange.left.equals(StorageService.getPartitioner().getMinimumToken()))
        {
            if (logger.isDebugEnabled())
                logger.debug("restricted single token match for query " + queryRange);
            return Collections.singletonList(queryRange);
        }

        TokenMetadata tokenMetadata = StorageService.instance.getTokenMetadata();

        List<AbstractBounds> ranges = new ArrayList<AbstractBounds>();
        // divide the queryRange into pieces delimited by the ring and minimum tokens
        Iterator<Token> ringIter = TokenMetadata.ringIterator(tokenMetadata.sortedTokens(), queryRange.left, true);
        AbstractBounds remainder = queryRange;
        while (ringIter.hasNext())
        {
            Token token = ringIter.next();
            if (remainder == null || !(remainder.left.equals(token) || remainder.contains(token)))
                // no more splits
                break;
            Pair<AbstractBounds,AbstractBounds> splits = remainder.split(token);
            if (splits.left != null)
                ranges.add(splits.left);
            remainder = splits.right;
        }
        if (remainder != null)
            ranges.add(remainder);
        if (logger.isDebugEnabled())
            logger.debug("restricted ranges for query " + queryRange + " are " + ranges);

        return ranges;
    }

    public long getReadOperations()
    {
        return readStats.getOpCount();
    }

    public long getTotalReadLatencyMicros()
    {
        return readStats.getTotalLatencyMicros();
    }

    public double getRecentReadLatencyMicros()
    {
        return readStats.getRecentLatencyMicros();
    }

    public long getRangeOperations()
    {
        return rangeStats.getOpCount();
    }

    public long getTotalRangeLatencyMicros()
    {
        return rangeStats.getTotalLatencyMicros();
    }

    public double getRecentRangeLatencyMicros()
    {
        return rangeStats.getRecentLatencyMicros();
    }

    public long getHintOperations()
    {
        return hintStats.getOpCount();
    }

    public long getTotalHintLatencyMicros()
    {
        return hintStats.getTotalLatencyMicros();
    }
    
    public long getRecentWeakReadsLocal()
    {
        return weakParLocal.getAndSet(0);
    }

    public long getRecentWeakReadsRemote()
    {
        return weakParRemote.getAndSet(0);
    }

    public long getRecentWeakConsistencyAll()
    {
        return weakParConsistencyAll.getAndSet(0);
    }

    public long getRecentWeakConsistencyUnder()
    {
        return weakParConsistencyUnder.getAndSet(0);
    }

    public static void countWeakConsistencyAll()
    {
        weakParConsistencyAll.incrementAndGet();
    }

    public static void countWeakConsistencyUnder()
    {
        weakParConsistencyUnder.incrementAndGet();
    }

    /**
     * @return the recentReadRepairs
     */
    public long getRecentReadRepairs()
    {
        return recentReadRepairs.getAndSet(0);
    }
    
    public static void countReadRepair()
    {
        recentReadRepairs.incrementAndGet();
    }

    public double getRecentHintLatencyMicros()
    {
        return hintStats.getRecentLatencyMicros();
    }
    
    public long[] getRecentHintHistogram()
    {
        return hintStats.getRecentLatencyHistogramMicros();
    }

    public long[] getTotalHintHistogram()
    {
        return hintStats.getTotalLatencyHistogramMicros();
    }

    public long getWriteOperations()
    {
        return writeStats.getOpCount();
    }

    public long getTotalWriteLatencyMicros()
    {
        return writeStats.getTotalLatencyMicros();
    }

    public double getRecentWriteLatencyMicros()
    {
        return writeStats.getRecentLatencyMicros();
    }

    public String getHintedHandoffEnabled()
    {
        if (hintedHandoffEnabled)
            return HintedHandOffManager.instance().getClass()==HintedHandOffManager.class ? "true" : "hintlog";
        else
            return "false";
    }

    public void setHintedHandoffEnabled(String config) throws ConfigurationException
    {
        DatabaseDescriptor.setHintedHandoffManager(config);
        
        hintedHandoffEnabled = DatabaseDescriptor.hintedHandoffEnabled();
    }
    
    public void setHintedHandoffWriteLatencyThreshold(int millis)
    {
        hintedHandoffWriteLatencyThreshold = millis;
    }
    
    public int getHintedHandoffWriteLatencyThreshold()
    {
        return hintedHandoffWriteLatencyThreshold;
    }

    public static boolean isHintedHandoffEnabled()
    {
        return hintedHandoffEnabled;
    }
    
    /**
     * @return the laggedHints
     */
    public long getTotalLaggedHints()
    {
        return laggedHints.get();
    }
    
    public boolean getParallelWeakRead()
    {
        return DatabaseDescriptor.getParallelWeakRead();
    }
    
    /**
     * @param parallelWeakRead the parallelWeakRead to set
     */
    public void setParallelWeakRead(boolean parallelWeakRead)
    {
        DatabaseDescriptor.setParallelWeakRead(parallelWeakRead);
    }
    

    static class weakReadLocalCallable implements Callable<Object>
    {
        private final ReadCommand command;
        private final long start = System.currentTimeMillis();

        weakReadLocalCallable(ReadCommand command)
        {
            this.command = command;
        }

        public Object call() throws IOException
        {
            if (logger.isDebugEnabled())
                logger.debug("weakreadlocal reading " + command);

            Table table = Table.open(command.table);
            Row row = command.getRow(table);
            StorageService.instance.doConsistencyCheck(row, command, FBUtilities.getLocalAddress());

            MessagingService.instance.addLatency(FBUtilities.getLocalAddress(), System.currentTimeMillis() - start);
            return row;
        }
    }
    
    /**
     * @author Oleg Anastasyev<oa@hq.one.lv>
     *
     */
    static class WeakReadParallelLocalCallable implements Callable<Object>
    {
        private final ReadCommand command;
        private final ParallelWeakResponseHandler handler;
        private final long start = System.currentTimeMillis();
        
        /**
         * @param command
         * @param handler
         */
        private WeakReadParallelLocalCallable(ReadCommand command,
                ParallelWeakResponseHandler handler)
        {
            this.command = command;
            this.handler = handler;
        }



        /* (non-Javadoc)
         * @see java.util.concurrent.Callable#call()
         */
        @Override
        public Object call() throws Exception
        {
            if (logger.isDebugEnabled())
                logger.debug("weakreadlocalPar reading " + command);

            Table table = Table.open(command.table);
            Row row = command.getRow(table);
            
            handler.localResponse(row);

            MessagingService.instance.addLatency(FBUtilities.getLocalAddress(), System.currentTimeMillis() - start);

            return row;
        }

    }
    
}
