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

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.ComponentConfiguration;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Row;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;

/**
 * An {@link HbaseEventSerializer} which parses columns based on a supplied 
 * regular expression and column name list. 
 * 
 * Note that if the regular expression does not return the correct number of
 * groups for a particular event, or it does not correctly match an event,
 * the event is silently dropped.
 * 
 * Row keys for each event consist of a timestamp concatenated with an
 * identifier which enforces uniqueness of keys across flume agents.
 * 
 * See static constant variables for configuration options.
 */
public class RegexHbaseEventSerializer implements HbaseEventSerializer {
  // Config vars
  /** Regular expression used to parse groups from event data. */
  public static final String REGEX_CONFIG = "regex";
  public static final String REGEX_DEFAULT = "(.*)";
  
  /** Whether to ignore case when performing regex matches. */
  public static final String IGNORE_CASE_CONFIG = "regexIgnoreCase";
  public static final boolean INGORE_CASE_DEFAULT = false;
  
  /** Comma separated list of column names to place matching groups in. */
  public static final String COL_NAME_CONFIG = "colNames";
  public static final String COLUMN_NAME_DEFAULT = "payload";

  
  /* This is a nonce used in HBase row-keys, such that the same row-key
   * never gets written more than once from within this JVM. */
  protected static final AtomicInteger nonce = new AtomicInteger(0);
  protected static String randomKey = RandomStringUtils.randomAlphanumeric(10);
  
  protected byte[] cf;
  private byte[] payload;
  private List<byte[]> colNames = Lists.newArrayList();
  private boolean regexIgnoreCase;
  private Pattern inputPattern;
  
  @Override
  public void configure(Context context) {
    String regex = context.getString(REGEX_CONFIG, REGEX_DEFAULT);
    regexIgnoreCase = context.getBoolean(IGNORE_CASE_CONFIG, 
        INGORE_CASE_DEFAULT);
    inputPattern = Pattern.compile(regex, Pattern.DOTALL
        + (regexIgnoreCase ? Pattern.CASE_INSENSITIVE : 0));
    
    String colNameStr = context.getString(COL_NAME_CONFIG, COLUMN_NAME_DEFAULT);
    String[] columnNames = colNameStr.split(",");
    for (String s: columnNames) { 
      colNames.add(s.getBytes(Charsets.UTF_8));
    }
  }

  @Override
  public void configure(ComponentConfiguration conf) {
  }

  @Override
  public void initialize(Event event, byte[] columnFamily) {
    this.payload = event.getBody();
    this.cf = columnFamily;
  }
  
  /**
   * Returns a row-key with the following format:
   * [time in millis]-[random key]-[nonce]
   */
  protected byte[] getRowKey(Calendar cal) {
    /* NOTE: This key generation strategy has the following properties:
     * 
     * 1) Within a single JVM, the same row key will never be duplicated.
     * 2) Amongst any two JVM's operating at different time periods (according
     *    to their respective clocks), the same row key will never be 
     *    duplicated.
     * 3) Amongst any two JVM's operating concurrently (according to their
     *    respective clocks), the odds of duplicating a row-key are non-zero
     *    but infinitesimal. This would require simultaneous collision in (a) 
     *    the timestamp (b) the respective nonce and (c) the random string.
     *    The string is necessary since (a) and (b) could collide if a fleet
     *    of Flume agents are restarted in tandem.
     *    
     *  Row-key uniqueness is important because conflicting row-keys will cause
     *  data loss. */
    String rowKey = String.format("%s-%s-%s", cal.getTimeInMillis(),
        randomKey, nonce.getAndIncrement());
    return rowKey.getBytes(Charsets.UTF_8);
  }
  
  protected byte[] getRowKey() {
    return getRowKey(Calendar.getInstance());
  }
  
  @Override
  public List<Row> getActions() throws FlumeException {
    List<Row> actions = Lists.newArrayList();
    byte[] rowKey;
    Matcher m = inputPattern.matcher(new String(payload));
    if (!m.matches()) {
      return Lists.newArrayList();
    }
    
    if (m.groupCount() != colNames.size()) {
      return Lists.newArrayList();
    }
    
  	try {
      rowKey = getRowKey();
      Put put = new Put(rowKey);
      
      for (int i = 0; i < colNames.size(); i++) {
        put.add(cf, colNames.get(i), m.group(i + 1).getBytes(Charsets.UTF_8));
      }
      actions.add(put);
  	}
  	catch (Exception e) {
  	  throw new FlumeException("Could not get row key!", e);
  	}
  	return actions;
  }

  @Override
  public List<Increment> getIncrements() {
    return Lists.newArrayList();
  }

  @Override
  public void close() {  }
}