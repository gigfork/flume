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
package org.apache.flume.sink.hbase;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.CounterGroup;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import java.security.PrivilegedExceptionAction;
import org.apache.hadoop.hbase.security.User;


/**
 *
 * A simple sink which reads events from a channel and writes them to HBase.
 * The Hbase configution is picked up from the first <tt>hbase-site.xml</tt>
 * encountered in the classpath. This sink supports batch reading of
 * events from the channel, and writing them to Hbase, to minimize the number
 * of flushes on the hbase tables. To use this sink, it has to be configured
 * with certain mandatory parameters:<p>
 * <tt>table: </tt> The name of the table in Hbase to write to. <p>
 * <tt>columnFamily: </tt> The column family in Hbase to write to.<p>
 * This sink will commit each transaction if the table's write buffer size is
 * reached or if the number of events in the current transaction reaches the
 * batch size, whichever comes first.<p>
 * Other optional parameters are:<p>
 * <tt>serializer:</tt> A class implementing {@link HBaseEventSerializer}.
 *  An instance of
 * this class will be used to write out events to hbase.<p>
 * <tt>serializer.*:</tt> Passed in the configure() method to serializer
 * as an object of {@link org.apache.flume.Context}.<p>
 * <tt>batchSize: </tt>This is the batch size used by the client. This is the
 * maximum number of events the sink will commit per transaction. The default
 * batch size is 100 events.
 * <p>
 *
 * <strong>Note: </strong> While this sink flushes all events in a transaction
 * to HBase in one shot, Hbase does not guarantee atomic commits on multiple
 * rows. So if a subset of events in a batch are written to disk by Hbase and
 * Hbase fails, the flume transaction is rolled back, causing flume to write
 * all the events in the transaction all over again, which will cause
 * duplicates. The serializer is expected to take care of the handling of
 * duplicates etc. HBase also does not support batch increments, so if
 * multiple increments are returned by the serializer, then HBase failure
 * will cause them to be re-written, when HBase comes back up.
 */
public class HBaseSink extends AbstractSink implements Configurable {
  private String tableName;
  private byte[] columnFamily;
  private HTable table;
  private long batchSize;
  private Configuration config;
  private CounterGroup counterGroup = new CounterGroup();
  private static final Logger logger = LoggerFactory.getLogger(HBaseSink.class);
  private HbaseEventSerializer serializer;
  private String eventSerializerType;
  private Context serializerContext;
  private String kerberosPrincipal;
  private String kerberosKeytab;
  private User hbaseUser;
  private boolean enableWal = true;

  public HBaseSink(){
    this(HBaseConfiguration.create());
  }

  public HBaseSink(Configuration conf){
    this.config = conf;
  }

  @Override
  public void start(){
    Preconditions.checkArgument(table == null, "Please call stop " +
        "before calling start on an old instance.");
    try {
      if (HBaseSinkSecurityManager.isSecurityEnabled(config)) {
        hbaseUser = HBaseSinkSecurityManager.login(config, null,
          kerberosPrincipal, kerberosKeytab);
      }
    } catch (Exception ex) {
      throw new FlumeException("Failed to login to HBase using "
        + "provided credentials.", ex);
    }
    try {
      table = runPrivileged(new PrivilegedExceptionAction<HTable>() {
        @Override
        public HTable run() throws Exception {
          HTable table = new HTable(config, tableName);
          table.setAutoFlush(false);
          // Flush is controlled by us. This ensures that HBase changing
          // their criteria for flushing does not change how we flush.
          return table;
        }
      });
    } catch (Exception e) {
      logger.error("Could not load table, " + tableName +
          " from HBase", e);
      throw new FlumeException("Could not load table, " + tableName +
          " from HBase", e);
    }
    try {
      if (!runPrivileged(new PrivilegedExceptionAction<Boolean>() {
        @Override
        public Boolean run() throws IOException {
          return table.getTableDescriptor().hasFamily(columnFamily);
        }
      })) {
        throw new IOException("Table " + tableName
                + " has no such column family " + Bytes.toString(columnFamily));
      }
    } catch (Exception e) {
      //Get getTableDescriptor also throws IOException, so catch the IOException
      //thrown above or by the getTableDescriptor() call.
      throw new FlumeException("Error getting column family from HBase."
              + "Please verify that the table " + tableName + " and Column Family, "
              + Bytes.toString(columnFamily) + " exists in HBase, and the"
              + " current user has permissions to access that table.", e);
    }

    super.start();
  }

  @Override
  public void stop(){
    try {
      table.close();
      table = null;
    } catch (IOException e) {
      throw new FlumeException("Error closing table.", e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void configure(Context context){
    tableName = context.getString(HBaseSinkConfigurationConstants.CONFIG_TABLE);
    String cf = context.getString(
        HBaseSinkConfigurationConstants.CONFIG_COLUMN_FAMILY);
    batchSize = context.getLong(
        HBaseSinkConfigurationConstants.CONFIG_BATCHSIZE, new Long(100));
    serializerContext = new Context();
    //If not specified, will use HBase defaults.
    eventSerializerType = context.getString(
        HBaseSinkConfigurationConstants.CONFIG_SERIALIZER);
    Preconditions.checkNotNull(tableName,
        "Table name cannot be empty, please specify in configuration file");
    Preconditions.checkNotNull(cf,
        "Column family cannot be empty, please specify in configuration file");
    //Check foe event serializer, if null set event serializer type
    if(eventSerializerType == null || eventSerializerType.isEmpty()) {
      eventSerializerType =
          "org.apache.flume.sink.hbase.SimpleHbaseEventSerializer";
      logger.info("No serializer defined, Will use default");
    }
    serializerContext.putAll(context.getSubProperties(
            HBaseSinkConfigurationConstants.CONFIG_SERIALIZER_PREFIX));
    columnFamily = cf.getBytes(Charsets.UTF_8);
    try {
      Class<? extends HbaseEventSerializer> clazz =
          (Class<? extends HbaseEventSerializer>)
          Class.forName(eventSerializerType);
      serializer = clazz.newInstance();
      serializer.configure(serializerContext);
    } catch (Exception e) {
      logger.error("Could not instantiate event serializer." , e);
      Throwables.propagate(e);
    }
    kerberosKeytab = context.getString(HBaseSinkConfigurationConstants.CONFIG_KEYTAB, "");
    kerberosPrincipal = context.getString(HBaseSinkConfigurationConstants.CONFIG_PRINCIPAL, "");

    enableWal = context.getBoolean(HBaseSinkConfigurationConstants
      .CONFIG_ENABLE_WAL, HBaseSinkConfigurationConstants.DEFAULT_ENABLE_WAL);
    logger.info("The write to WAL option is set to: " + String.valueOf(enableWal));
    if(!enableWal) {
      logger.warn("HBase Sink's enableWal configuration is set to false. All " +
        "writes to HBase will have WAL disabled, and any data in the " +
        "memstore of this region in the Region Server could be lost!");
    }
  }

  @Override
  public Status process() throws EventDeliveryException {
    Status status = Status.READY;
    Channel channel = getChannel();
    Transaction txn = channel.getTransaction();
    List<Row> actions = new LinkedList<Row>();
    List<Increment> incs = new LinkedList<Increment>();
    txn.begin();
    for(long i = 0; i < batchSize; i++) {
      Event event = channel.take();
      if(event == null){
        status = Status.BACKOFF;
        counterGroup.incrementAndGet("channel.underflow");
        break;
      } else {
        serializer.initialize(event, columnFamily);
        actions.addAll(serializer.getActions());
        incs.addAll(serializer.getIncrements());
      }
    }
    putEventsAndCommit(actions, incs, txn);
    return status;
  }

  private void putEventsAndCommit(final List<Row> actions, final List<Increment> incs,
      Transaction txn) throws EventDeliveryException {
    try {
      runPrivileged(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          for(Row r : actions) {
            if(r instanceof Put) {
              ((Put)r).setWriteToWAL(enableWal);
            }
            // Newer versions of HBase - Increment implements Row.
            if(r instanceof Increment) {
              ((Increment)r).setWriteToWAL(enableWal);
            }
          }
          table.batch(actions);
          return null;
        }
      });

      runPrivileged(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          for (final Increment i : incs) {
            i.setWriteToWAL(enableWal);
            table.increment(i);
          }
          return null;
        }
      });

      txn.commit();
      counterGroup.incrementAndGet("transaction.success");
    } catch (Throwable e) {
      try{
        txn.rollback();
      } catch (Exception e2) {
        logger.error("Exception in rollback. Rollback might not have been" +
            "successful." , e2);
      }
      counterGroup.incrementAndGet("transaction.rollback");
      logger.error("Failed to commit transaction." +
          "Transaction rolled back.", e);
      if(e instanceof Error || e instanceof RuntimeException){
        logger.error("Failed to commit transaction." +
            "Transaction rolled back.", e);
        Throwables.propagate(e);
      } else {
        logger.error("Failed to commit transaction." +
            "Transaction rolled back.", e);
        throw new EventDeliveryException("Failed to commit transaction." +
            "Transaction rolled back.", e);
      }
    } finally {
      txn.close();
    }
  }
  private <T> T runPrivileged(final PrivilegedExceptionAction<T> action)
          throws Exception {
    if(hbaseUser != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("Calling runAs as hbase user: " + hbaseUser.getName());
      }
      return hbaseUser.runAs(action);
    } else {
      return action.run();
    }
  }
}
