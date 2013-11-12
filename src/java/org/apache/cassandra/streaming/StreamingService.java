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

package org.apache.cassandra.streaming;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.RateControl;
import org.apache.log4j.Logger;

public class StreamingService implements StreamingServiceMBean
{
    private static final Logger logger = Logger.getLogger(StreamingService.class);
    public static final String MBEAN_OBJECT_NAME = "org.apache.cassandra.streaming:type=StreamingService";
    public static final StreamingService instance = new StreamingService();
    
    private RateControl streamRateControl = null;

    private StreamingService()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName(MBEAN_OBJECT_NAME));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getStatus()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Receiving from:\n");
        for (InetAddress source : StreamInManager.getSources())
        {
            sb.append(String.format(" %s:\n", source.getHostAddress()));
            for (PendingFile pf : StreamInManager.getIncomingFiles(source))
            {
                sb.append(String.format("  %s %d/%d\n", pf.getSourceFile(), pf.getPtr(), pf.getExpectedBytes()));
            }
        }
        sb.append("Sending to:\n");
        for (InetAddress dest : StreamOutManager.getDestinations())
        {
            sb.append(String.format(" %s:\n", dest.getHostAddress()));
            for (PendingFile pf : StreamOutManager.getPendingFiles(dest))
            {
                sb.append(String.format("  %s %d/%d\n", pf.getSourceFile(), pf.getPtr(), pf.getExpectedBytes()));
            }
        }
        return sb.toString();
        
    }

    /** hosts receiving outgoing streams. */
    public Set<InetAddress> getStreamDestinations()
    {
        return StreamOutManager.getDestinations();
    }

    /** outgoing streams */
    public List<String> getOutgoingFiles(String host) throws IOException
    {
        List<String> files = new ArrayList<String>();
        // first, verify that host is a destination. calling StreamOutManager.get will put it in the collection
        // leading to false positives in the future.
        Set<InetAddress> existingDestinations = getStreamDestinations();
        InetAddress dest = InetAddress.getByName(host);
        if (!existingDestinations.contains(dest))
            return files;
        
        StreamOutManager manager = StreamOutManager.get(dest);
        for (PendingFile f : manager.getFiles())
            files.add(String.format("%s %d/%d", f.getSourceFile(), f.getPtr(), f.getExpectedBytes()));
        return files;
    }

    /** hosts sending incoming streams */
    public Set<InetAddress> getStreamSources()
    {
        return StreamInManager.getSources();
    }

    /** details about incoming streams. */
    public List<String> getIncomingFiles(String host) throws IOException
    {
        List<String> files = new ArrayList<String>();
        for (PendingFile pf : StreamInManager.getIncomingFiles(InetAddress.getByName(host)))
        {
            files.add(String.format("%s: %s %d/%d", pf.getTable(), pf.getSourceFile(), pf.getPtr(), pf.getExpectedBytes()));
        }
        return files;
    }
    
    public int getStreamInMBits()
    {
        return DatabaseDescriptor.getStreamInMBits();
    }
    
    public void setStreamInMBits(int newMBits)
    {
        DatabaseDescriptor.setStreamInMBits(newMBits);

        if (streamRateControl!=null)
            streamRateControl = new RateControl(newMBits);
    }

    /**
     * @return the streamRateControl
     */
    public RateControl getStreamRateControl()
    {
        RateControl rc = streamRateControl;
        return rc == null ? streamRateControl = new RateControl(getStreamInMBits()) : rc;
    }
    
    /* (non-Javadoc)
     * @see org.apache.cassandra.streaming.StreamingServiceMBean#cancelStreamOut(java.lang.String)
     */
    @Override
    public void cancelStreamOut(String host) throws UnknownHostException
    {
        InetAddress endpoint = InetAddress.getByName(host);
        StreamOutManager manager = StreamOutManager.get(endpoint);
        
        manager.reset();
    }
}
