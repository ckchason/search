/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.hadoop;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Instantiate a record writer that will build a Solr index.
 * 
 * A zip file containing the solr config and additional libraries is expected to
 * be passed via the distributed cache. The incoming written records are
 * converted via the specified document converter, and written to the index in
 * batches. When the job is done, the close copies the index to the destination
 * output file system. <h2>Configuration Parameters</h2>
 * <ul>
 * <li>solr.record.writer.batch.size - the number of documents in a batch that
 * is sent to the indexer.</li>
 * <li>mapred.task.id - To build the unique temporary index directory file name.
 * </li>
 * <li>solr.output.format.setup - {@link SolrOutputFormat.SETUP_OK} The path to
 * the configuration zip file.</li>
 * <li> {@link SolrOutputFormat.zipName} - The file name of the configuration
 * file.</li>
 * <li>solr.document.converter.class -
 * {@link SolrDocumentConverter.CONVERTER_NAME_KEY} the class used to convert
 * the {@link #write} key, values into a {@link SolrInputDocument}. Set via
 * {@link SolrDocumentConverter}.
 * </ul>
 */
public class SolrRecordWriter<K, V> extends RecordWriter<K, V> {
  private static final Logger LOG = LoggerFactory.getLogger(SolrRecordWriter.class);

  public final static List<String> allowedConfigDirectories = new ArrayList<String>(
      Arrays.asList(new String[] { "conf", "lib" }));

  public final static Set<String> requiredConfigDirectories = new HashSet<String>();
  static {
    requiredConfigDirectories.add("conf");
  }

  /**
   * Return the list of directories names that may be included in the
   * configuration data passed to the tasks.
   * 
   * @return an UnmodifiableList of directory names
   */
  public static List<String> getAllowedConfigDirectories() {
    return Collections.unmodifiableList(allowedConfigDirectories);
  }

  /**
   * check if the passed in directory is required to be present in the
   * configuration data set.
   * 
   * @param directory The directory to check
   * @return true if the directory is required.
   */
  public static boolean isRequiredConfigDirectory(final String directory) {
    return requiredConfigDirectories.contains(directory);
  }

  private int batchSize;

  /** The path that the final index will be written to */

  /** The location in a local temporary directory that the index is built in. */

//  /**
//   * If true, create a zip file of the completed index in the final storage
//   * location A .zip will be appended to the final output name if it is not
//   * already present.
//   */
//  private boolean outputZipFile = false;

  HeartBeater heartBeater = null;

  private BatchWriter batchWriter = null;

  private static HashMap<TaskID, Reducer<?,?,?,?>.Context> contextMap = new HashMap<TaskID, Reducer<?,?,?,?>.Context>();
  
  protected boolean isClosing() {
    return closing;
  }

  protected void setClosing(boolean closing) {
    this.closing = closing;
  }

  /** If true, writes will throw an exception */
  private volatile boolean closing = false;

  public static String getOutFileName(TaskAttemptContext context, String prefix) {
    TaskID taskId = context.getTaskAttemptID().getTaskID();
    int partition = taskId.getId();
    NumberFormat nf = NumberFormat.getInstance();
    nf.setMinimumIntegerDigits(7);
    nf.setGroupingUsed(false);
    NumberFormat nf2 = NumberFormat.getInstance();
    nf2.setMinimumIntegerDigits(3);
    nf2.setGroupingUsed(false);
    StringBuilder result = new StringBuilder();
    result.append(prefix);
    result.append("-");
    result.append(nf.format(partition));
    result.append("-");
    result.append(nf2.format(context.getTaskAttemptID().getId()));
    return result.toString();
  }

  public SolrRecordWriter(TaskAttemptContext context) {
    Configuration conf = context.getConfiguration();
    batchSize = SolrOutputFormat.getBatchSize(conf);

    // setLogLevel("org.apache.solr.core", "WARN");
    // setLogLevel("org.apache.solr.update", "WARN");

    heartBeater = new HeartBeater(context);
    try {
      heartBeater.needHeartBeat();

      Path solrHomeDir = SolrRecordWriter.findSolrConfig(conf);
      FileSystem fs = FileSystem.get(conf);
      Path outputShardDir = new Path(FileOutputFormat.getOutputPath(context),
          getOutFileName(context, "shard"));

      EmbeddedSolrServer solr = createEmbeddedSolrServer(solrHomeDir, fs, outputShardDir);
      batchWriter = new BatchWriter(solr, batchSize,
          context.getTaskAttemptID().getTaskID(),
          SolrOutputFormat.getSolrWriterThreadCount(conf),
          SolrOutputFormat.getSolrWriterQueueSize(conf));

    } catch (Exception e) {
      throw new IllegalStateException(String.format(
          "Failed to initialize record writer for %s, %s", context.getJobName(), conf
              .get("mapred.task.id")), e);
    } finally {
      heartBeater.cancelHeartBeat();
    }
  }

  public static EmbeddedSolrServer createEmbeddedSolrServer(Path solrHomeDir, FileSystem fs, Path outputShardDir)
      throws IOException, ParserConfigurationException, SAXException {

    if (solrHomeDir == null) {
      throw new IOException("Unable to find solr home setting");
    }
    LOG.info("SolrHome: " + solrHomeDir.toUri());

    Properties props = new Properties();
    // FIXME note this is odd (no scheme) given Solr doesn't currently
    // support uris (just abs/relative path)
    Path solrDataDir = new Path(outputShardDir, "data");
    if (!fs.exists(solrDataDir) && !fs.mkdirs(solrDataDir)) {
      throw new IOException("Unsable to create " + solrDataDir);
    }
    // FIXME hdfsdirectory expects absolute path, solr doesn't generally
    // though
    String dataDirStr = solrDataDir.toUri().toString().substring(6);
    props.setProperty("solr.data.dir", dataDirStr);
    props.setProperty("solr.home", solrHomeDir.toString());

    SolrResourceLoader loader = new SolrResourceLoader(solrHomeDir.toString(),
        null, props);

    LOG.info(String
        .format(
            "Constructed instance information solr.home %s (%s), instance dir %s, conf dir %s, writing index to %s, with permdir %s",
            solrHomeDir, solrHomeDir.toUri(), loader.getInstanceDir(),
            loader.getConfigDir(), dataDirStr, outputShardDir));

    CoreContainer container = new CoreContainer(loader);
    CoreDescriptor descr = new CoreDescriptor(container, "core1",
        solrHomeDir.toString());
    descr.setDataDir(dataDirStr);
    descr.setCoreProperties(props);
    SolrCore core = container.create(descr);
    container.register(core, false);
    EmbeddedSolrServer solr = new EmbeddedSolrServer(container, "core1");
    return solr;
  }

  public static void incrementCounter(TaskID taskId, String groupName, String counterName, long incr) {
    Reducer<?,?,?,?>.Context context = contextMap.get(taskId);
    if (context != null) {
      context.getCounter(groupName, counterName).increment(incr);
    }
  }

  public static void addReducerContext(Reducer<?,?,?,?>.Context context) {
    TaskID taskID = context.getTaskAttemptID().getTaskID();
    contextMap.put(taskID, context);
  }

  public static Path findSolrConfig(Configuration conf) throws IOException {
    Path solrHome = null;
    // FIXME when mrunit supports the new cache apis
    //URI[] localArchives = context.getCacheArchives();
    Path[] localArchives = DistributedCache.getLocalCacheArchives(conf);
    if (localArchives.length == 0) {
      throw new IOException(String.format(
          "No local cache archives, where is %s:%s", SolrOutputFormat
              .getSetupOk(), SolrOutputFormat.getZipName(conf)));
    }
    for (Path unpackedDir : localArchives) {
      // Only logged if debugging
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Examining unpack directory %s for %s",
            unpackedDir, SolrOutputFormat.getZipName(conf)));

        ProcessBuilder lsCmd = new ProcessBuilder(new String[] { "/bin/ls",
            "-lR", unpackedDir.toString() });
        lsCmd.redirectErrorStream();
        Process ls = lsCmd.start();
        byte[] buf = new byte[16 * 1024];
        InputStream all = ls.getInputStream();
        try {
          int count;
          while ((count = all.read(buf)) >= 0) {
            System.err.write(buf, 0, count);
          }
        } catch (IOException ignore) {
        } finally {
          all.close();
        }
        String exitValue;
        try {
          exitValue = String.valueOf(ls.waitFor());
        } catch (InterruptedException e) {
          exitValue = "interrupted";
        }
        System.err.format("Exit value is %s%n", exitValue);
      }
      if (unpackedDir.getName().equals(SolrOutputFormat.getZipName(conf))) {

        solrHome = unpackedDir;
        break;
      }
    }
    return solrHome;
  }

  /**
   * Write a record. This method accumulates records in to a batch, and when
   * {@link #batchSize} items are present flushes it to the indexer. The writes
   * can take a substantial amount of time, depending on {@link #batchSize}. If
   * there is heavy disk contention the writes may take more than the 600 second
   * default timeout.
   */
  @Override
  public void write(K key, V value) throws IOException {
    if (isClosing()) {
      throw new IOException("Index is already closing");
    }
    heartBeater.needHeartBeat();
    try {
      try {
        SolrInputDocumentWritable sidw = (SolrInputDocumentWritable) value;
        batchWriter.queueBatch(Collections.singleton(sidw.getSolrInputDocument()));
      } catch (SolrServerException e) {
        throw new IOException(e);
      }
    } finally {
      heartBeater.cancelHeartBeat();
    }

  }

  @Override
  public void close(TaskAttemptContext context) throws IOException, InterruptedException {
    if (context != null) {
      heartBeater.setProgress(context);
    }
    try {
      heartBeater.needHeartBeat();
      batchWriter.close(context);
//      if (outputZipFile) {
//        context.setStatus("Writing Zip");
//        packZipFile(); // Written to the perm location
//      } else {
//        context.setStatus("Copying Index");
//        fs.completeLocalOutput(perm, temp); // copy to dfs
//      }
    } catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException(e);
    } finally {
      heartBeater.cancelHeartBeat();
//      File tempFile = new File(temp.toString());
//      if (tempFile.exists()) {
//        FileUtils.forceDelete(new File(temp.toString()));
//      }
    }

    context.setStatus("Done");
  }

//  private void packZipFile() throws IOException {
//    FSDataOutputStream out = null;
//    ZipOutputStream zos = null;
//    int zipCount = 0;
//    LOG.info("Packing zip file for " + perm);
//    try {
//      out = fs.create(perm, false);
//      zos = new ZipOutputStream(out);
//
//      String name = perm.getName().replaceAll(".zip$", "");
//      LOG.info("adding index directory" + temp);
//      zipCount = zipDirectory(conf, zos, name, temp.toString(), temp);
//      /**
//      for (String configDir : allowedConfigDirectories) {
//        if (!isRequiredConfigDirectory(configDir)) {
//          continue;
//        }
//        final Path confPath = new Path(solrHome, configDir);
//        LOG.info("adding configdirectory" + confPath);
//
//        zipCount += zipDirectory(conf, zos, name, solrHome.toString(), confPath);
//      }
//      **/
//    } catch (Throwable ohFoo) {
//      LOG.error("packZipFile exception", ohFoo);
//      if (ohFoo instanceof RuntimeException) {
//        throw (RuntimeException) ohFoo;
//      }
//      if (ohFoo instanceof IOException) {
//        throw (IOException) ohFoo;
//      }
//      throw new IOException(ohFoo);
//
//    } finally {
//      if (zos != null) {
//        if (zipCount == 0) { // If no entries were written, only close out, as
//                             // the zip will throw an error
//          LOG.error("No entries written to zip file " + perm);
//          fs.delete(perm, false);
//          // out.close();
//        } else {
//          LOG.info(String.format("Wrote %d items to %s for %s", zipCount, perm,
//              temp));
//          zos.close();
//        }
//      }
//    }
//  }
//
//  /**
//   * Write a file to a zip output stream, removing leading path name components
//   * from the actual file name when creating the zip file entry.
//   * 
//   * The entry placed in the zip file is <code>baseName</code>/
//   * <code>relativePath</code>, where <code>relativePath</code> is constructed
//   * by removing a leading <code>root</code> from the path for
//   * <code>itemToZip</code>.
//   * 
//   * If <code>itemToZip</code> is an empty directory, it is ignored. If
//   * <code>itemToZip</code> is a directory, the contents of the directory are
//   * added recursively.
//   * 
//   * @param zos The zip output stream
//   * @param baseName The base name to use for the file name entry in the zip
//   *        file
//   * @param root The path to remove from <code>itemToZip</code> to make a
//   *        relative path name
//   * @param itemToZip The path to the file to be added to the zip file
//   * @return the number of entries added
//   * @throws IOException
//   */
//  static public int zipDirectory(final Configuration conf,
//      final ZipOutputStream zos, final String baseName, final String root,
//      final Path itemToZip) throws IOException {
//    LOG
//        .info(String
//            .format("zipDirectory: %s %s %s", baseName, root, itemToZip));
//    LocalFileSystem localFs = FileSystem.getLocal(conf);
//    int count = 0;
//
//    final FileStatus itemStatus = localFs.getFileStatus(itemToZip);
//    if (itemStatus.isDirectory()) {
//      final FileStatus[] statai = localFs.listStatus(itemToZip);
//
//      // Add a directory entry to the zip file
//      final String zipDirName = relativePathForZipEntry(itemToZip.toUri()
//          .getPath(), baseName, root);
//      final ZipEntry dirZipEntry = new ZipEntry(zipDirName
//          + Path.SEPARATOR_CHAR);
//      LOG.info(String.format("Adding directory %s to zip", zipDirName));
//      zos.putNextEntry(dirZipEntry);
//      zos.closeEntry();
//      count++;
//
//      if (statai == null || statai.length == 0) {
//        LOG.info(String.format("Skipping empty directory %s", itemToZip));
//        return count;
//      }
//      for (FileStatus status : statai) {
//        count += zipDirectory(conf, zos, baseName, root, status.getPath());
//      }
//      LOG.info(String.format("Wrote %d entries for directory %s", count,
//          itemToZip));
//      return count;
//    }
//
//    final String inZipPath = relativePathForZipEntry(itemToZip.toUri()
//        .getPath(), baseName, root);
//
//    if (inZipPath.length() == 0) {
//      LOG.warn(String.format("Skipping empty zip file path for %s (%s %s)",
//          itemToZip, root, baseName));
//      return 0;
//    }
//
//    // Take empty files in case the place holder is needed
//    FSDataInputStream in = null;
//    try {
//      in = localFs.open(itemToZip);
//      final ZipEntry ze = new ZipEntry(inZipPath);
//      ze.setTime(itemStatus.getModificationTime());
//      // Comments confuse looking at the zip file
//      // ze.setComment(itemToZip.toString());
//      zos.putNextEntry(ze);
//
//      IOUtils.copyBytes(in, zos, conf, false);
//      zos.closeEntry();
//      LOG.info(String.format("Wrote %d entries for file %s", count, itemToZip));
//      return 1;
//    } finally {
//      in.close();
//    }
//
//  }
//
//  static String relativePathForZipEntry(final String rawPath,
//      final String baseName, final String root) {
//    String relativePath = rawPath.replaceFirst(Pattern.quote(root.toString()),
//        "");
//    LOG.info(String.format("RawPath %s, baseName %s, root %s, first %s",
//        rawPath, baseName, root, relativePath));
//
//    if (relativePath.startsWith(Path.SEPARATOR)) {
//      relativePath = relativePath.substring(1);
//    }
//    LOG.info(String.format(
//        "RawPath %s, baseName %s, root %s, post leading slash %s", rawPath,
//        baseName, root, relativePath));
//    if (relativePath.isEmpty()) {
//      LOG.warn(String.format(
//          "No data after root (%s) removal from raw path %s", root, rawPath));
//      return baseName;
//    }
//    // Construct the path that will be written to the zip file, including
//    // removing any leading '/' characters
//    String inZipPath = baseName + Path.SEPARATOR_CHAR + relativePath;
//
//    LOG.info(String.format("RawPath %s, baseName %s, root %s, inZip 1 %s",
//        rawPath, baseName, root, inZipPath));
//    if (inZipPath.startsWith(Path.SEPARATOR)) {
//      inZipPath = inZipPath.substring(1);
//    }
//    LOG.info(String.format("RawPath %s, baseName %s, root %s, inZip 2 %s",
//        rawPath, baseName, root, inZipPath));
//
//    return inZipPath;
//
//  }
//  
  /*
  static boolean setLogLevel(String packageName, String level) {
    Log logger = LogFactory.getLog(packageName);
    if (logger == null) {
      return false;
    }
    // look for: org.apache.commons.logging.impl.SLF4JLocationAwareLog
    LOG.warn("logger class:"+logger.getClass().getName());
    if (logger instanceof Log4JLogger) {
      process(((Log4JLogger) logger).getLogger(), level);
      return true;
    }
    if (logger instanceof Jdk14Logger) {
      process(((Jdk14Logger) logger).getLogger(), level);
      return true;
    }
    return false;
  }

  public static void process(org.apache.log4j.Logger log, String level) {
    if (level != null) {
      log.setLevel(org.apache.log4j.Level.toLevel(level));
    }
  }

  public static void process(java.util.logging.Logger log, String level) {
    if (level != null) {
      log.setLevel(java.util.logging.Level.parse(level));
    }
  }
  */
}
