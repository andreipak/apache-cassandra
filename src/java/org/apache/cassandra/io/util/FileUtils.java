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

package org.apache.cassandra.io.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;

import org.apache.cassandra.io.SSTable;
import org.apache.log4j.Logger;


public class FileUtils
{
    private static Logger logger_ = Logger.getLogger(FileUtils.class);
    private static final DecimalFormat df_ = new DecimalFormat("#.##");
    private static final double kb_ = 1024d;
    private static final double mb_ = 1024*1024d;
    private static final double gb_ = 1024*1024*1024d;
    private static final double tb_ = 1024*1024*1024*1024d;

    public static void deleteWithConfirm(File file) throws IOException
    {
        assert file.exists() : "attempted to delete non-existing file " + file.getName();
        if (logger_.isDebugEnabled())
            logger_.debug("Deleting " + file.getName());
        if (!file.delete())
        {
            throw new IOException("Failed to delete " + file.getAbsolutePath());
        }
    }

    public static class FileComparator implements Comparator<File>
    {
        public int compare(File f, File f2)
        {
            return (int)(f.lastModified() - f2.lastModified());
        }
    }

    public static void createDirectory(String directory) throws IOException
    {
        File file = new File(directory);
        if (!file.exists())
        {
            if (!file.mkdirs())
            {
                throw new IOException("unable to mkdirs " + directory);
            }
        }
    }

    public static void createFile(String directory) throws IOException
    {
        File file = new File(directory);
        if ( !file.exists() )
            file.createNewFile();
    }

    public static boolean isExists(String filename) throws IOException
    {
        File file = new File(filename);
        return file.exists();
    }

    public static boolean delete(String file)
    {
        File f = new File(file);
        return f.delete();
    }

    public static boolean delete(List<String> files) throws IOException
    {
        boolean bVal = true;
        for ( int i = 0; i < files.size(); ++i )
        {
            String file = files.get(i);
            bVal = delete(file);
            if (bVal)
            {
            	if (logger_.isDebugEnabled())
            	  logger_.debug("Deleted file " + file);
                files.remove(i);
            }
        }
        return bVal;
    }

    public static void delete(File[] files) throws IOException
    {
        for ( File file : files )
        {
            file.delete();
        }
    }

    public static String stringifyFileSize(double value)
    {
        double d = 0d;
        if ( value >= tb_ )
        {
            d = value / tb_;
            String val = df_.format(d);
            return val + " TB";
        }
        else if ( value >= gb_ )
        {
            d = value / gb_;
            String val = df_.format(d);
            return val + " GB";
        }
        else if ( value >= mb_ )
        {
            d = value / mb_;
            String val = df_.format(d);
            return val + " MB";
        }
        else if ( value >= kb_ )
        {
            d = value / kb_;
            String val = df_.format(d);
            return val + " KB";
        }
        else
        {       
            String val = df_.format(value);
            return val + " bytes";
        }        
    }
    
    /**
     * Deletes all files and subdirectories under "dir".
     * @param dir Directory to be deleted
     * @throws IOException if any part of the tree cannot be deleted
     */
    public static void deleteDir(File dir) throws IOException
    {
        if (dir.isDirectory())
        {
            for (String aChildren : dir.list())
                deleteDir(new File(dir, aChildren));
        }

        // The directory is now empty so now it can be smoked
        deleteWithConfirm(dir);
    }
    
    /**
     * 
     * @param dir
     * @param mask
     * @return number of bytes occupied by data, index, not counting compacted files
     */
    public static long occupiedLiveDataSpace(File dir )
    {
        File[] files = dir.listFiles(new SuffixFileFilter("-Data.db","-Index.db"));
        long occupied = 0;
        
        for (File file : files) 
        {
            if (new File(SSTable.compactedFilename(file.getAbsolutePath())).exists())
            {
//                logger_.info("Skipping compacted data "+file);
                continue; // this file is compacted and should not be accounted (will never read)
            }
            
            occupied+=file.length();
        }
        
        return occupied;
    }
    
    public static class SuffixFileFilter implements FilenameFilter {
        
        /** The filename suffixes to search for */
        private String[] suffixes;

        /**
         * Constructs a new Suffix file filter for a single extension.
         * 
         * @param suffix  the suffix to allow, must not be null
         * @throws IllegalArgumentException if the suffix is null
         */
        public SuffixFileFilter(String suffix) {
            if (suffix == null) {
                throw new IllegalArgumentException("The suffix must not be null");
            }
            this.suffixes = new String[] {suffix};
        }

        /**
         * Constructs a new Suffix file filter for an array of suffixs.
         * <p>
         * The array is not cloned, so could be changed after constructing the
         * instance. This would be inadvisable however.
         * 
         * @param suffixes  the suffixes to allow, must not be null
         * @throws IllegalArgumentException if the suffix array is null
         */
        public SuffixFileFilter(String... suffixes) {
            if (suffixes == null) {
                throw new IllegalArgumentException("The array of suffixes must not be null");
            }
            this.suffixes = suffixes;
        }

        /**
         * Constructs a new Suffix file filter for a list of suffixes.
         * 
         * @param suffixes  the suffixes to allow, must not be null
         * @throws IllegalArgumentException if the suffix list is null
         * @throws ClassCastException if the list does not contain Strings
         */
        public SuffixFileFilter(List suffixes) {
            if (suffixes == null) {
                throw new IllegalArgumentException("The list of suffixes must not be null");
            }
            this.suffixes = (String[]) suffixes.toArray(new String[suffixes.size()]);
        }

        /**
         * Checks to see if the filename ends with the suffix.
         * 
         * @param file  the File to check
         * @return true if the filename ends with one of our suffixes
         */
        public boolean accept(File file) {
            String name = file.getName();
            for (int i = 0; i < this.suffixes.length; i++) {
                if (name.endsWith(this.suffixes[i])) {
                    return true;
                }
            }
            return false;
        }
        
        /**
         * Checks to see if the filename ends with the suffix.
         * 
         * @param file  the File directory
         * @param name  the filename
         * @return true if the filename ends with one of our suffixes
         */
        public boolean accept(File file, String name) {
            for (int i = 0; i < this.suffixes.length; i++) {
                if (name.endsWith(this.suffixes[i])) {
                    return true;
                }
            }
            return false;
        }
    }
}
