/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.utils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iotdb.cluster.exception.ConfigInconsistentException;
import org.apache.iotdb.cluster.exception.StartUpCheckFailureException;
import org.apache.iotdb.cluster.rpc.thrift.CheckStatusResponse;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.server.service.MetaAsyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterUtils {
  private static final Logger logger = LoggerFactory.getLogger(ClusterUtils.class);

  public static final int STARTUP_CHECK_THREAD_POOL_SIZE = 6;

  public static final int WAIT_START_UP_CHECK_TIME = 5;

  public static final TimeUnit WAIT_START_UP_CHECK_TIME_UNIT = TimeUnit.MINUTES;

  public static final long START_UP_TIME_THRESHOLD = 1; // minute

  public static final long START_UP_CHECK_TIME_INTERVAL = 5; // second

  private ClusterUtils() {
    // util class
  }

  public static boolean checkSeedNodes(boolean isClusterEstablished, List<Node> localSeedNodes,
      List<Node> remoteSeedNodes) {
    return isClusterEstablished ? seedNodesContains(localSeedNodes, remoteSeedNodes)
        : seedNodesEquals(localSeedNodes, remoteSeedNodes);
  }

  public static boolean seedNodesEquals(List<Node> thisNodeList, List<Node> thatNodeList) {
    Node[] thisNodeArray = thisNodeList.toArray(new Node[0]);
    Node[] thatNodeArray = thatNodeList.toArray(new Node[0]);
    Arrays.sort(thisNodeArray, ClusterUtils::compareSeedNode);
    Arrays.sort(thatNodeArray, ClusterUtils::compareSeedNode);
    if (thisNodeArray.length != thatNodeArray.length) {
      return false;
    } else {
      for (int i = 0; i < thisNodeArray.length; i++) {
        if (compareSeedNode(thisNodeArray[i], thatNodeArray[i]) != 0) {
          return false;
        }
      }
      return true;
    }
  }

  public static int compareSeedNode(Node thisSeedNode, Node thatSeedNode) {
    int ipCompare = thisSeedNode.getIp().compareTo(thatSeedNode.getIp());
    if (ipCompare != 0) {
      return ipCompare;
    } else {
      int metaPortCompare = thisSeedNode.getMetaPort() - thatSeedNode.getMetaPort();
      if (metaPortCompare != 0) {
        return metaPortCompare;
      } else {
        return thisSeedNode.getDataPort() - thatSeedNode.getDataPort();
      }
    }
  }

  public static boolean seedNodesContains(List<Node> seedNodeList, List<Node> subSeedNodeList) {
    // Because identifier is not compared here, List.contains() is not suitable
    if (subSeedNodeList == null) {
      return false;
    }
    seedNodeList.sort(ClusterUtils::compareSeedNode);
    subSeedNodeList.sort(ClusterUtils::compareSeedNode);
    int i = 0;
    int j = 0;
    while (i < seedNodeList.size() && j < subSeedNodeList.size()) {
      int compareResult = compareSeedNode(seedNodeList.get(i), subSeedNodeList.get(j));
      if (compareResult > 0) {
        if (logger.isInfoEnabled()) {
          logger.info("Node {} not found in cluster", subSeedNodeList.get(j));
        }
        return false;
      } else if (compareResult < 0) {
        i++;
      } else {
        j++;
      }
    }
    return j == subSeedNodeList.size();
  }

  public static void examineCheckStatusResponse(CheckStatusResponse response,
      AtomicInteger consistentNum, AtomicInteger inconsistentNum) {
    boolean partitionIntervalEquals = response.partitionalIntervalEquals;
    boolean hashSaltEquals = response.hashSaltEquals;
    boolean replicationNumEquals = response.replicationNumEquals;
    boolean seedNodeListEquals = response.seedNodeEquals;
    if (!partitionIntervalEquals) {
      logger.info(
          "Local partition interval conflicts with the majority of seed nodes.");
    }
    if (!hashSaltEquals) {
      logger
          .info("Local hash salt conflicts with the majority of seed nodes.");
    }
    if (!replicationNumEquals) {
      logger.info(
          "Local replication number conflicts with the majority of seed nodes.");
    }
    if (!seedNodeListEquals) {
      logger.info("Local seed node list conflicts with the majority of seed nodes.");
    }
    if (partitionIntervalEquals
        && hashSaltEquals
        && replicationNumEquals
        && seedNodeListEquals) {
      consistentNum.incrementAndGet();
    } else {
      inconsistentNum.incrementAndGet();
    }
  }

  public static boolean analyseStartUpCheckResult(int consistentNum, int inconsistentNum,
      int totalSeedNum, long timeElapsed) {
    if (consistentNum >= (totalSeedNum + 1) / 2) {
      // break the loop and establish the cluster
      return true;
    } else if (inconsistentNum >= (totalSeedNum + 1) / 2) {
      // this node is not consistent with the cluster, shut down
      throw new ConfigInconsistentException();
    } else {
      // If reach the start up time threshold, shut down.
      // Otherwise, wait for a while, start the loop again.
      if (timeElapsed > START_UP_TIME_THRESHOLD * 60 * 1000) {
        throw new StartUpCheckFailureException();
      } else {
        try {
          Thread.sleep(START_UP_CHECK_TIME_INTERVAL * 1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.error("Unexpected interruption when waiting for next start up check", e);
        }
      }
    }
    return false;
  }
}