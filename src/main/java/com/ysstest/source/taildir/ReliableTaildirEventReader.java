/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.ysstest.source.taildir;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.gson.stream.JsonReader;
import com.ysstest.source.utils.RenameDir;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.annotations.InterfaceAudience;
import org.apache.flume.annotations.InterfaceStability;
import org.apache.flume.client.avro.ReliableEventReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public class ReliableTaildirEventReader implements ReliableEventReader {
    private static final Logger logger = LoggerFactory.getLogger(ReliableTaildirEventReader.class);

    private final List<TaildirMatcher> taildirCache;
    private final Table<String, String, String> headerTable;

    private TailFile currentFile = null;
    private Map<Long, TailFile> tailFiles = Maps.newHashMap();
    private long updateTime;
    private boolean addByteOffset;
    private boolean cachePatternMatching;
    private boolean committed = true;
    private final boolean annotateFileName;
    private final String fileNameHeader;
    private final String xmlNode;
    private final String currentRecord;
    private final String csvSeparator;
    private Boolean renameFlie;
    private Boolean headFile;
    private int eventLines;
    private String prefixStr;


    private String sourceA;
    private String sourceB;
    private String regexA;
    private String regexB;
    private String regexFsdFour;
    private String fsdFourBytes;
    private String regexFsdSix;
    private String fsdSixBytes;
    private String regexFsdJY;
    private String fsdJYBytes;
    private static RenameDir renameDir = new RenameDir();
    ;

    /**
     * Create a ReliableTaildirEventReader to watch the given directory.
     */
    private ReliableTaildirEventReader(Map<String, String> filePaths,
                                       Table<String, String, String> headerTable, String positionFilePath,
                                       boolean skipToEnd, boolean addByteOffset, boolean cachePatternMatching,
                                       boolean annotateFileName, String fileNameHeader, String xmlNode,
                                       String currentRecord, String csvSeparator, Boolean directoryDate,
                                       Boolean renameFlie, int eventLines, boolean headFile, String prefixStr,
                                       String sourceA, String sourceB, String regexA, String regexB, String regexFsdFour,
                                       String fsdFourBytes, String regexFsdSix, String fsdSixBytes, String regexFsdJY, String fsdJYBytes) throws IOException {
        // Sanity checks
        Preconditions.checkNotNull(filePaths);
        Preconditions.checkNotNull(positionFilePath);

        if (logger.isDebugEnabled()) {
            logger.debug("Initializing {} with directory={}, metaDir={}",
                    new Object[]{ReliableTaildirEventReader.class.getSimpleName(), filePaths});
        }

        List<TaildirMatcher> taildirCache = Lists.newArrayList();
        for (Entry<String, String> e : filePaths.entrySet()) {
            taildirCache.add(new TaildirMatcher(e.getKey(), e.getValue(), cachePatternMatching, directoryDate, prefixStr));
        }
        logger.info("taildirCache: " + taildirCache.toString());
        logger.info("headerTable: " + headerTable.toString());

        this.taildirCache = taildirCache;
        this.headerTable = headerTable;
        this.addByteOffset = addByteOffset;
        this.cachePatternMatching = cachePatternMatching;
        this.annotateFileName = annotateFileName;
        this.fileNameHeader = fileNameHeader;
        this.xmlNode = xmlNode;
        this.currentRecord = currentRecord;
        this.csvSeparator = csvSeparator;
        this.renameFlie = renameFlie;
        this.eventLines = eventLines;
        this.headFile = headFile;
        this.prefixStr = prefixStr;
        this.sourceA = sourceA;
        this.sourceB = sourceB;
        this.regexA = regexA;
        this.regexB = regexB;
        this.regexFsdFour = regexFsdFour;
        this.fsdFourBytes = fsdFourBytes;
        this.regexFsdSix = regexFsdSix;
        this.fsdSixBytes = fsdSixBytes;
        this.regexFsdJY = regexFsdJY;
        this.fsdJYBytes = fsdJYBytes;
        updateTailFiles(skipToEnd);

        logger.info("Updating position from position file: " + positionFilePath);
        loadPositionFile(positionFilePath);
    }

    /**
     * Load a position file which has the last read position of each file.
     * If the position file exists, update tailFiles mapping.
     */
    public void loadPositionFile(String filePath) {
        Long inode, pos;
        String path;
        FileReader fr = null;
        JsonReader jr = null;
        try {
            fr = new FileReader(filePath);
            jr = new JsonReader(fr);
            jr.beginArray();
            while (jr.hasNext()) {
                inode = null;
                pos = null;
                path = null;
                jr.beginObject();
                while (jr.hasNext()) {
                    switch (jr.nextName()) {
                        case "inode":
                            inode = jr.nextLong();
                            break;
                        case "pos":
                            pos = jr.nextLong();
                            break;
                        case "file":
                            path = jr.nextString();
                            break;
                    }
                }
                jr.endObject();

                for (Object v : Arrays.asList(inode, pos, path)) {
                    Preconditions.checkNotNull(v, "Detected missing value in position file. "
                            + "inode: " + inode + ", pos: " + pos + ", path: " + path);
                }
                TailFile tf = tailFiles.get(inode);
                if (tf != null && tf.updatePos(path, inode, pos)) {
                    tailFiles.put(inode, tf);
                } else {
                    logger.info("Missing file: " + path + ", inode: " + inode + ", pos: " + pos);
                }
            }
            jr.endArray();
        } catch (FileNotFoundException e) {
            logger.info("File not found: " + filePath + ", not updating position");
        } catch (IOException e) {
            logger.error("Failed loading positionFile: " + filePath, e);
        } finally {
            try {
                if (fr != null) fr.close();
                if (jr != null) jr.close();
            } catch (IOException e) {
                logger.error("Error: " + e.getMessage(), e);
            }
        }
    }

    public Map<Long, TailFile> getTailFiles() {
        return tailFiles;
    }

    public void setCurrentFile(TailFile currentFile) {
        this.currentFile = currentFile;
    }

    @Override
    public Event readEvent() throws IOException {
        List<Event> events = readEvents(1);
        if (events.isEmpty()) {
            return null;
        }
        return events.get(0);
    }

    @Override
    public List<Event> readEvents(int numEvents) throws IOException {
        return readEvents(numEvents, false);
    }

    @VisibleForTesting
    public List<Event> readEvents(TailFile tf, int numEvents) throws IOException {
        setCurrentFile(tf);
        return readEvents(numEvents, true);
    }

    public List<Event> readEvents(int numEvents, boolean backoffWithoutNL)
            throws IOException {
        if (!committed) {
            if (currentFile == null) {
                throw new IllegalStateException("current file does not exist. " + currentFile.getPath());
            }
            logger.info("Last read was never committed - resetting position");
            long lastPos = currentFile.getPos();
            currentFile.updateFilePos(lastPos);
        }
        List<Event> events = currentFile.readEvents(numEvents, backoffWithoutNL, addByteOffset);
        if (events.isEmpty()) {
            return events;
        }

        Map<String, String> headers = currentFile.getHeaders();
        if (annotateFileName || (headers != null && !headers.isEmpty())) {
            for (Event event : events) {
                if (headers != null && !headers.isEmpty()) {
                    event.getHeaders().putAll(headers);
                }
                //添加文件的相对路径信息
                String filename = currentFile.getPath().replace(currentFile.getParentDir(), "");
                //去掉文件的原有后缀名
//                if (filename.length() > 4) {
//                    filename = filename.substring(0, filename.length() - 4);
//                }
                if (annotateFileName) {

                    event.getHeaders().put(fileNameHeader, filename);
                    //添加文件夹
                    renameDir.rename(event, filename, fileNameHeader);
                }
            }
        }
        committed = false;
        return events;
    }

    @Override
    public void close() throws IOException {
        for (TailFile tf : tailFiles.values()) {
            if (tf.getRaf() != null) tf.getRaf().close();
        }
    }

    /**
     * Commit the last lines which were read.
     */
    @Override
    public void commit() throws IOException {
        if (!committed && currentFile != null) {
            long pos = currentFile.getLineReadPos();
            currentFile.setPos(pos);
            currentFile.setLastUpdated(updateTime);
            committed = true;
        }
    }

    /**
     * Update tailFiles mapping if a new file is created or appends are detected
     * to the existing file.
     */
    public List<Long> updateTailFiles(boolean skipToEnd) throws IOException {
        updateTime = System.currentTimeMillis();
        List<Long> updatedInodes = Lists.newArrayList();
        //遍历多个组
        for (TaildirMatcher taildir : taildirCache) {
            Map<String, String> headers = headerTable.row(taildir.getFileGroup());
            //返回符合过滤的条件
            for (File f : taildir.getMatchingFiles()) {
                long inode = getInode(f);
                TailFile tf = tailFiles.get(inode);
                if (tf == null || !tf.getPath().equals(f.getAbsolutePath())) {
                    long startPos = skipToEnd ? f.length() : 0;

                    tf = openFile(f, headers, inode, startPos, taildir.getParentDir());
                } else {
                    //当文件修改时进行读取
                    boolean updated = tf.getLastUpdated() < f.lastModified() || tf.getPos() != f.length();
                    if (updated) {
                        if (tf.getRaf() == null) {
                            tf = openFile(f, headers, inode, tf.getPos(), taildir.getParentDir());
                        }
                        if (f.length() < tf.getPos()) {
                            logger.info("Pos " + tf.getPos() + " is larger than file size! "
                                    + "Restarting from pos 0, file: " + tf.getPath() + ", inode: " + inode);
                            tf.updatePos(tf.getPath(), inode, 0);
                        }
                    }
                    tf.setNeedTail(updated);
                }
                tailFiles.put(inode, tf);
                updatedInodes.add(inode);
            }
        }
        return updatedInodes;
    }

    public List<Long> updateTailFiles() throws IOException {
        return updateTailFiles(false);
    }


    private long getInode(File file) throws IOException {
        long inode = (long) Files.getAttribute(file.toPath(), "unix:ino");
        return inode;
    }

    private TailFile openFile(File file, Map<String, String> headers, long inode, long pos, String parentDir) {
        try {
            logger.info("Opening file: " + file + ", inode: " + inode + ", pos: " + pos + ", parentDir: " + parentDir);
            return new TailFile(file, headers, inode, pos, parentDir, xmlNode,
                    currentRecord, csvSeparator, renameFlie, eventLines,
                    headFile, prefixStr, sourceA, sourceB, regexA, regexB,
                    regexFsdFour, fsdFourBytes, regexFsdSix, fsdSixBytes, regexFsdJY, fsdJYBytes);
        } catch (IOException e) {
            throw new FlumeException("Failed opening file: " + file, e);
        }
    }

    /**
     * Special builder class for ReliableTaildirEventReader
     */
    public static class Builder {
        private Map<String, String> filePaths;
        private Table<String, String, String> headerTable;
        private String positionFilePath;
        private boolean skipToEnd;
        private boolean addByteOffset;
        private boolean cachePatternMatching;
        private Boolean annotateFileName =
                TaildirSourceConfigurationConstants.DEFAULT_FILE_HEADER;
        private String fileNameHeader =
                TaildirSourceConfigurationConstants.DEFAULT_FILENAME_HEADER_KEY;
        private String setXmlNode =
                TaildirSourceConfigurationConstants.DEFAULT_XML_NODE;
        private String setCurrentRecord =
                TaildirSourceConfigurationConstants.DEFAULT_CURRENT_RECORD;
        private String setCsvSeparator =
                TaildirSourceConfigurationConstants.DEFAULT_SEPARATOR;
        private boolean setDirectoryDate =
                TaildirSourceConfigurationConstants.DEFAULT_DIRECTORY_DATE;
        private boolean setRenameFlie =
                TaildirSourceConfigurationConstants.DEFAULT_RENAME_FLIE;
        private Integer setEventLines =
                TaildirSourceConfigurationConstants.DEFAULT_EVENT_LINES;
        private Boolean setHead =
                TaildirSourceConfigurationConstants.DEFAULT_HEAD;
        private String setPrefixStr =
                TaildirSourceConfigurationConstants.DEFAULT_PREFIXSTR;
        private String setSourceA =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCESEPARATOR_A;
        private String setSourceB =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_SEPARATOR_B;
        private String setRegexA =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_REGEX_A;
        private String setRegexB =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_REGEX_B;
        private String setRegexFsdFour =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_REGEX_FSD_FOUR;
        private String setFsdFourBytes =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_FSD_FOUR_BYTES;
        private String setRegexFsdSix =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_REGEX_FSD_SIX;
        private String setFsdSixBytes =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_FSD_SIX_BYTES;
        private String setRegexFsdJY =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_REGEX_FSD_JY;
        private String setFsdJYBytes =
                TaildirSourceConfigurationConstants.DEFAULT_SOURCE_FSD_JY_BYTES;

        public Builder sourceA(String setSourceA) {
            this.setSourceA = setSourceA;
            return this;
        }

        public Builder sourceB(String setSourceB) {
            this.setSourceB = setSourceB;
            return this;
        }

        public Builder regexA(String setRegexA) {
            this.setRegexA = setRegexA;
            return this;
        }

        public Builder regexB(String setRegexB) {
            this.setRegexB = setRegexB;
            return this;
        }

        public Builder regexFsdFour(String setRegexFsdFour) {
            this.setRegexFsdFour = setRegexFsdFour;
            return this;
        }

        public Builder fsdFourBytes(String setFsdFourBytes) {
            this.setFsdFourBytes = setFsdFourBytes;
            return this;
        }

        public Builder regexFsdSix(String setRegexFsdSix) {
            this.setRegexFsdSix = setRegexFsdSix;
            return this;
        }

        public Builder fsdSixBytes(String setFsdSixBytes) {
            this.setFsdSixBytes = setFsdSixBytes;
            return this;
        }

        public Builder regexFsdJY(String setRegexFsdJY) {
            this.setRegexFsdJY = setRegexFsdJY;
            return this;
        }

        public Builder fsdJYBytes(String setFsdJYBytes) {
            this.setFsdJYBytes = setFsdJYBytes;
            return this;
        }


        public Builder prefixStr(String prefixStr) {
            this.setPrefixStr = prefixStr;
            return this;
        }

        public Builder head(boolean setHead) {
            this.setHead = setHead;
            return this;
        }

        public Builder eventLines(int setEventLines) {
            this.setEventLines = setEventLines;
            return this;
        }

        public Builder directoryDate(boolean setDirectoryDate) {
            this.setDirectoryDate = setDirectoryDate;
            return this;
        }

        public Builder renameFlie(boolean setRenameFlie) {
            this.setRenameFlie = setRenameFlie;
            return this;
        }

        public Builder xmlNode(String xmlNode) {
            this.setXmlNode = xmlNode;
            return this;
        }

        public Builder currentRecord(String currentRecord) {
            this.setCurrentRecord = currentRecord;
            return this;
        }

        public Builder csvSeparator(String csvSeparator) {
            this.setCsvSeparator = csvSeparator;
            return this;
        }

        public Builder filePaths(Map<String, String> filePaths) {
            this.filePaths = filePaths;
            return this;
        }

        public Builder headerTable(Table<String, String, String> headerTable) {
            this.headerTable = headerTable;
            return this;
        }


        public Builder positionFilePath(String positionFilePath) {
            this.positionFilePath = positionFilePath;
            return this;
        }

        public Builder skipToEnd(boolean skipToEnd) {
            this.skipToEnd = skipToEnd;
            return this;
        }

        public Builder addByteOffset(boolean addByteOffset) {
            this.addByteOffset = addByteOffset;
            return this;
        }

        public Builder cachePatternMatching(boolean cachePatternMatching) {
            this.cachePatternMatching = cachePatternMatching;
            return this;
        }

        public Builder annotateFileName(boolean annotateFileName) {
            this.annotateFileName = annotateFileName;
            return this;
        }

        public Builder fileNameHeader(String fileNameHeader) {
            this.fileNameHeader = fileNameHeader;
            return this;
        }

        public ReliableTaildirEventReader build() throws IOException {
            return new ReliableTaildirEventReader(filePaths, headerTable, positionFilePath, skipToEnd,
                    addByteOffset, cachePatternMatching,
                    annotateFileName, fileNameHeader, setXmlNode,
                    setCurrentRecord, setCsvSeparator, setDirectoryDate,
                    setRenameFlie, setEventLines, setHead, setPrefixStr, setSourceA, setSourceB, setRegexA, setRegexB,
                    setRegexFsdFour, setFsdFourBytes, setRegexFsdSix, setFsdSixBytes, setRegexFsdJY, setFsdJYBytes);
        }
    }

}
