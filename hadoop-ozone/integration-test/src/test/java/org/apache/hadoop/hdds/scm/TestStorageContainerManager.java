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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.HddsUtils;
import org.apache.hadoop.hdds.conf.DefaultConfigManager;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeType;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.IncrementalContainerReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMCommandProto;
import org.apache.hadoop.hdds.ratis.RatisHelper;
import org.apache.hadoop.hdds.scm.block.DeletedBlockLog;
import org.apache.hadoop.hdds.scm.block.SCMBlockDeletingService;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerNotFoundException;
import org.apache.hadoop.hdds.scm.container.ContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.IncrementalContainerReportHandler;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerWithPipeline;
import org.apache.hadoop.hdds.scm.container.replication.ReplicationManager;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.ha.RatisUtil;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMHAUtils;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServerImpl;
import org.apache.hadoop.hdds.scm.node.DatanodeInfo;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.node.NodeStatus;
import org.apache.hadoop.hdds.scm.server.ContainerReportQueue;
import org.apache.hadoop.hdds.scm.server.SCMClientProtocolServer;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.ContainerReportFromDatanode;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.IncrementalContainerReportFromDatanode;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.server.events.EventExecutor;
import org.apache.hadoop.hdds.server.events.EventPublisher;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.hdds.server.events.FixedThreadPoolWithAffinityExecutor;
import org.apache.hadoop.hdds.utils.HddsVersionInfo;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.StaticMapping;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.OzoneTestUtils;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeConfiguration;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine.EndpointStateMachine;
import org.apache.hadoop.ozone.container.common.states.endpoint.HeartbeatEndpointTask;
import org.apache.hadoop.ozone.container.common.states.endpoint.VersionEndpointTask;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.protocol.commands.CloseContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.CommandForDatanode;
import org.apache.hadoop.ozone.protocol.commands.DeleteBlocksCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.Time;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_CONTAINER_REPORT_INTERVAL;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_HEARTBEAT_INTERVAL;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_COMMAND_STATUS_REPORT_INTERVAL;
import static org.apache.hadoop.hdds.scm.HddsWhiteboxTestUtils.setInternalState;
import static org.apache.hadoop.hdds.scm.HddsTestUtils.mockRemoteUser;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_INTERVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Test class that exercises the StorageContainerManager.
 */
@Timeout(900)
public class TestStorageContainerManager {
  private static XceiverClientManager xceiverClientManager;
  private static final Logger LOG = LoggerFactory.getLogger(
            TestStorageContainerManager.class);

  @BeforeAll
  public static void setup() throws IOException {
    xceiverClientManager = new XceiverClientManager(new OzoneConfiguration());
  }

  @AfterAll
  public static void cleanup() {
    if (xceiverClientManager != null) {
      xceiverClientManager.close();
    }
  }

  @AfterEach
  public void cleanupDefaults() {
    DefaultConfigManager.clearDefaultConfigs();
  }

  @Test
  public void testRpcPermission() throws Exception {
    // Test with default configuration
    OzoneConfiguration defaultConf = new OzoneConfiguration();
    testRpcPermissionWithConf(defaultConf, any -> false, "unknownUser");

    // Test with ozone.administrators defined in configuration
    String admins = "adminUser1, adminUser2";
    OzoneConfiguration ozoneConf = new OzoneConfiguration();
    ozoneConf.setStrings(OzoneConfigKeys.OZONE_ADMINISTRATORS, admins);
    // Non-admin user will get permission denied.
    // Admin user will pass the permission check.
    testRpcPermissionWithConf(ozoneConf, admins::contains,
        "unknownUser", "adminUser2");
  }

  private void testRpcPermissionWithConf(
      OzoneConfiguration ozoneConf,
      Predicate<String> isAdmin,
      String... usernames) throws Exception {
    MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(ozoneConf).build();
    try {
      cluster.waitForClusterToBeReady();
      for (String username : usernames) {
        testRpcPermission(cluster, username,
            !isAdmin.test(username));
      }
    } finally {
      cluster.shutdown();
    }
  }

  private void testRpcPermission(MiniOzoneCluster cluster,
      String fakeRemoteUsername, boolean expectPermissionDenied) {
    SCMClientProtocolServer mockClientServer = spy(
        cluster.getStorageContainerManager().getClientProtocolServer());

    mockRemoteUser(UserGroupInformation.createRemoteUser(fakeRemoteUsername));
    Exception ex = assertThrows(Exception.class, () -> mockClientServer.deleteContainer(
        ContainerTestHelper.getTestContainerID()));
    if (expectPermissionDenied) {
      verifyPermissionDeniedException(ex, fakeRemoteUsername);
    } else {
      // If passes permission check, it should fail with
      // container not exist exception.
      assertInstanceOf(ContainerNotFoundException.class, ex);
    }

    try {
      ContainerWithPipeline container2 = mockClientServer.allocateContainer(
          HddsProtos.ReplicationType.RATIS,
          HddsProtos.ReplicationFactor.ONE, OzoneConsts.OZONE);
      if (expectPermissionDenied) {
        fail("Operation should fail, expecting an IOException here.");
      } else {
        assertEquals(1, container2.getPipeline().getNodes().size());
      }
    } catch (Exception e) {
      verifyPermissionDeniedException(e, fakeRemoteUsername);
    }

    Exception e = assertThrows(Exception.class, () -> mockClientServer.getContainer(
        ContainerTestHelper.getTestContainerID()));
    if (expectPermissionDenied) {
      verifyPermissionDeniedException(e, fakeRemoteUsername);
    } else {
      // If passes permission check, it should fail with
      // key not exist exception.
      assertInstanceOf(ContainerNotFoundException.class, e);
    }
  }

  private void verifyPermissionDeniedException(Exception e, String userName) {
    String expectedErrorMessage = "Access denied for user "
        + userName + ". " + "SCM superuser privilege is required.";
    assertInstanceOf(IOException.class, e);
    assertEquals(expectedErrorMessage, e.getMessage());
  }

  @Test
  public void testBlockDeletionTransactions() throws Exception {
    int numKeys = 5;
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL, 100,
        TimeUnit.MILLISECONDS);
    DatanodeConfiguration datanodeConfiguration = conf.getObject(
        DatanodeConfiguration.class);
    datanodeConfiguration.setBlockDeletionInterval(Duration.ofMillis(100));
    conf.setFromObject(datanodeConfiguration);
    ScmConfig scmConfig = conf.getObject(ScmConfig.class);
    scmConfig.setBlockDeletionInterval(Duration.ofMillis(100));
    conf.setFromObject(scmConfig);

    conf.setTimeDuration(RatisHelper.HDDS_DATANODE_RATIS_PREFIX_KEY
        + ".client.request.write.timeout", 30, TimeUnit.SECONDS);
    conf.setTimeDuration(RatisHelper.HDDS_DATANODE_RATIS_PREFIX_KEY
        + ".client.request.watch.timeout", 30, TimeUnit.SECONDS);
    conf.setInt("hdds.datanode.block.delete.threads.max", 5);
    conf.setInt("hdds.datanode.block.delete.queue.limit", 32);
    conf.setTimeDuration(HDDS_HEARTBEAT_INTERVAL, 50,
        TimeUnit.MILLISECONDS);
    conf.setTimeDuration(HDDS_CONTAINER_REPORT_INTERVAL, 200,
        TimeUnit.MILLISECONDS);
    conf.setTimeDuration(HDDS_COMMAND_STATUS_REPORT_INTERVAL, 200,
        TimeUnit.MILLISECONDS);
    conf.setInt(ScmConfigKeys.OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 5);
    // Reset container provision size, otherwise only one container
    // is created by default.
    conf.setInt(ScmConfigKeys.OZONE_SCM_PIPELINE_OWNER_CONTAINER_COUNT,
        numKeys);

    MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
        .setHbInterval(50)
        .build();
    cluster.waitForClusterToBeReady();

    try {
      DeletedBlockLog delLog = cluster.getStorageContainerManager()
          .getScmBlockManager().getDeletedBlockLog();
      assertEquals(0, delLog.getNumOfValidTransactions());

      // Create {numKeys} random names keys.
      TestStorageContainerManagerHelper helper =
          new TestStorageContainerManagerHelper(cluster, conf);
      Map<String, OmKeyInfo> keyLocations = helper.createKeys(numKeys, 4096);
      // Wait for container report
      Thread.sleep(1000);
      for (OmKeyInfo keyInfo : keyLocations.values()) {
        OzoneTestUtils.closeContainers(keyInfo.getKeyLocationVersions(),
            cluster.getStorageContainerManager());
      }
      Map<Long, List<Long>> containerBlocks = createDeleteTXLog(
          cluster.getStorageContainerManager(),
          delLog, keyLocations, helper);

      // Verify a few TX gets created in the TX log.
      assertThat(delLog.getNumOfValidTransactions()).isGreaterThan(0);

      // Once TXs are written into the log, SCM starts to fetch TX
      // entries from the log and schedule block deletions in HB interval,
      // after sometime, all the TX should be proceed and by then
      // the number of containerBlocks of all known containers will be
      // empty again.
      GenericTestUtils.waitFor(() -> {
        try {
          if (SCMHAUtils.isSCMHAEnabled(cluster.getConf())) {
            cluster.getStorageContainerManager().getScmHAManager()
                .asSCMHADBTransactionBuffer().flush();
          }
          return delLog.getNumOfValidTransactions() == 0;
        } catch (IOException e) {
          return false;
        }
      }, 1000, 22000);
      assertTrue(helper.verifyBlocksWithTxnTable(containerBlocks));
      // Continue the work, add some TXs that with known container names,
      // but unknown block IDs.
      for (Long containerID : containerBlocks.keySet()) {
        // Add 2 TXs per container.
        Map<Long, List<Long>> deletedBlocks = new HashMap<>();
        List<Long> blocks = new ArrayList<>();
        blocks.add(RandomUtils.nextLong());
        blocks.add(RandomUtils.nextLong());
        deletedBlocks.put(containerID, blocks);
        addTransactions(cluster.getStorageContainerManager(), delLog,
            deletedBlocks);
      }

      // Verify a few TX gets created in the TX log.
      assertThat(delLog.getNumOfValidTransactions()).isGreaterThan(0);

      // These blocks cannot be found in the container, skip deleting them
      // eventually these TX will success.
      GenericTestUtils.waitFor(() -> {
        try {
          if (SCMHAUtils.isSCMHAEnabled(cluster.getConf())) {
            cluster.getStorageContainerManager().getScmHAManager()
                .asSCMHADBTransactionBuffer().flush();
          }
          return delLog.getFailedTransactions(-1, 0).size() == 0;
        } catch (IOException e) {
          return false;
        }
      }, 1000, 20000);
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testOldDNRegistersToReInitialisedSCM() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    MiniOzoneCluster cluster =
        MiniOzoneCluster.newBuilder(conf).setHbInterval(1000)
            .setHbProcessorInterval(3000).setNumDatanodes(1)
            .build();
    cluster.waitForClusterToBeReady();

    try {
      HddsDatanodeService datanode = cluster.getHddsDatanodes().get(0);
      StorageContainerManager scm = cluster.getStorageContainerManager();
      scm.stop();

      // re-initialise SCM with new clusterID

      GenericTestUtils.deleteDirectory(
          new File(scm.getScmStorageConfig().getStorageDir()));
      String newClusterId = UUID.randomUUID().toString();
      StorageContainerManager.scmInit(scm.getConfiguration(), newClusterId);
      scm = HddsTestUtils.getScmSimple(scm.getConfiguration());

      DatanodeStateMachine dsm = datanode.getDatanodeStateMachine();
      assertEquals(DatanodeStateMachine.DatanodeStates.RUNNING,
          dsm.getContext().getState());
      // DN Endpoint State has already gone through GetVersion and Register,
      // so it will be in HEARTBEAT state.
      for (EndpointStateMachine endpoint : dsm.getConnectionManager()
          .getValues()) {
        assertEquals(EndpointStateMachine.EndPointStates.HEARTBEAT,
            endpoint.getState());
      }
      GenericTestUtils.LogCapturer scmDnHBDispatcherLog =
          GenericTestUtils.LogCapturer.captureLogs(
              SCMDatanodeHeartbeatDispatcher.LOG);
      LogManager.getLogger(HeartbeatEndpointTask.class).setLevel(Level.DEBUG);
      GenericTestUtils.LogCapturer heartbeatEndpointTaskLog =
          GenericTestUtils.LogCapturer.captureLogs(HeartbeatEndpointTask.LOG);
      GenericTestUtils.LogCapturer versionEndPointTaskLog =
          GenericTestUtils.LogCapturer.captureLogs(VersionEndpointTask.LOG);
      // Initially empty
      assertThat(scmDnHBDispatcherLog.getOutput()).isEmpty();
      assertThat(versionEndPointTaskLog.getOutput()).isEmpty();
      // start the new SCM
      scm.start();
      // Initially DatanodeStateMachine will be in Running state
      assertEquals(DatanodeStateMachine.DatanodeStates.RUNNING,
          dsm.getContext().getState());
      // DN heartbeats to new SCM, SCM doesn't recognize the node, sends the
      // command to DN to re-register. Wait for SCM to send re-register command
      String expectedLog = String.format(
          "SCM received heartbeat from an unregistered datanode %s. "
              + "Asking datanode to re-register.",
          datanode.getDatanodeDetails());
      GenericTestUtils.waitFor(
          () -> scmDnHBDispatcherLog.getOutput().contains(expectedLog), 100,
          5000);
      ExitUtil.disableSystemExit();
      // As part of processing response for re-register, DN EndpointStateMachine
      // goes to GET-VERSION state which checks if there is already existing
      // version file on the DN & if the clusterID matches with that of the SCM
      // In this case, it won't match and gets InconsistentStorageStateException
      // and DN shuts down.
      String expectedLog2 = "Received SCM notification to register."
          + " Interrupt HEARTBEAT and transit to GETVERSION state.";
      GenericTestUtils.waitFor(
          () -> heartbeatEndpointTaskLog.getOutput().contains(expectedLog2),
          100, 5000);
      GenericTestUtils.waitFor(() -> dsm.getContext().getShutdownOnError(), 100,
          5000);
      assertEquals(DatanodeStateMachine.DatanodeStates.SHUTDOWN,
          dsm.getContext().getState());
      assertThat(versionEndPointTaskLog.getOutput()).contains(
          "org.apache.hadoop.ozone.common" +
              ".InconsistentStorageStateException: Mismatched ClusterIDs");
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testBlockDeletingThrottling() throws Exception {
    int numKeys = 15;
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setTimeDuration(HDDS_CONTAINER_REPORT_INTERVAL, 1, TimeUnit.SECONDS);
    conf.setInt(ScmConfigKeys.OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 5);
    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL,
        100, TimeUnit.MILLISECONDS);
    ScmConfig scmConfig = conf.getObject(ScmConfig.class);
    scmConfig.setBlockDeletionInterval(Duration.ofMillis(100));
    conf.setFromObject(scmConfig);
    conf.setInt(ScmConfigKeys.OZONE_SCM_PIPELINE_OWNER_CONTAINER_COUNT,
        numKeys);
    conf.setBoolean(HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);

    MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
        .setHbInterval(1000)
        .setHbProcessorInterval(3000)
        .setNumDatanodes(1)
        .build();
    cluster.waitForClusterToBeReady();
    cluster.waitForPipelineTobeReady(HddsProtos.ReplicationFactor.ONE, 30000);

    try {
      DeletedBlockLog delLog = cluster.getStorageContainerManager()
          .getScmBlockManager().getDeletedBlockLog();
      assertEquals(0, delLog.getNumOfValidTransactions());

      int limitSize = 1;
      // Reset limit value to 1, so that we only allow one TX is dealt per
      // datanode.
      SCMBlockDeletingService delService = cluster.getStorageContainerManager()
          .getScmBlockManager().getSCMBlockDeletingService();
      delService.setBlockDeleteTXNum(limitSize);

      // Create {numKeys} random names keys.
      TestStorageContainerManagerHelper helper =
          new TestStorageContainerManagerHelper(cluster, conf);
      Map<String, OmKeyInfo> keyLocations = helper.createKeys(numKeys, 4096);
      // Wait for container report
      Thread.sleep(5000);
      for (OmKeyInfo keyInfo : keyLocations.values()) {
        OzoneTestUtils.closeContainers(keyInfo.getKeyLocationVersions(),
            cluster.getStorageContainerManager());
      }

      createDeleteTXLog(cluster.getStorageContainerManager(),
          delLog, keyLocations, helper);
      // Verify a few TX gets created in the TX log.
      assertThat(delLog.getNumOfValidTransactions()).isGreaterThan(0);

      // Verify the size in delete commands is expected.
      GenericTestUtils.waitFor(() -> {
        NodeManager nodeManager = cluster.getStorageContainerManager()
            .getScmNodeManager();
        List<SCMCommand> commands = nodeManager.processHeartbeat(
            nodeManager.getNodes(NodeStatus.inServiceHealthy()).get(0));
        if (commands != null) {
          for (SCMCommand cmd : commands) {
            if (cmd.getType() == SCMCommandProto.Type.deleteBlocksCommand) {
              List<DeletedBlocksTransaction> deletedTXs =
                  ((DeleteBlocksCommand) cmd).blocksTobeDeleted();
              return deletedTXs != null && deletedTXs.size() == limitSize;
            }
          }
        }
        return false;
      }, 500, 10000);
    } finally {
      cluster.shutdown();
    }
  }

  private Map<Long, List<Long>> createDeleteTXLog(
      StorageContainerManager scm,
      DeletedBlockLog delLog,
      Map<String, OmKeyInfo> keyLocations,
      TestStorageContainerManagerHelper helper)
      throws IOException, TimeoutException {
    // These keys will be written into a bunch of containers,
    // gets a set of container names, verify container containerBlocks
    // on datanodes.
    Set<Long> containerNames = new HashSet<>();
    for (Map.Entry<String, OmKeyInfo> entry : keyLocations.entrySet()) {
      entry.getValue().getLatestVersionLocations().getLocationList()
          .forEach(loc -> containerNames.add(loc.getContainerID()));
    }

    // Total number of containerBlocks of these containers should be equal to
    // total number of containerBlocks via creation call.
    int totalCreatedBlocks = 0;
    for (OmKeyInfo info : keyLocations.values()) {
      totalCreatedBlocks += info.getKeyLocationVersions().size();
    }
    assertThat(totalCreatedBlocks).isGreaterThan(0);
    assertEquals(totalCreatedBlocks,
        helper.getAllBlocks(containerNames).size());

    // Create a deletion TX for each key.
    Map<Long, List<Long>> containerBlocks = Maps.newHashMap();
    for (OmKeyInfo info : keyLocations.values()) {
      List<OmKeyLocationInfo> list =
          info.getLatestVersionLocations().getLocationList();
      list.forEach(location -> {
        if (containerBlocks.containsKey(location.getContainerID())) {
          containerBlocks.get(location.getContainerID())
              .add(location.getBlockID().getLocalID());
        } else {
          List<Long> blks = Lists.newArrayList();
          blks.add(location.getBlockID().getLocalID());
          containerBlocks.put(location.getContainerID(), blks);
        }
      });
    }
    addTransactions(scm, delLog, containerBlocks);

    return containerBlocks;
  }

  @Test
  public void testSCMInitialization() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    final String path = GenericTestUtils.getTempPath(
        UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());

    UUID clusterId = UUID.randomUUID();
    String testClusterId = clusterId.toString();
    // This will initialize SCM
    StorageContainerManager.scmInit(conf, testClusterId);

    SCMStorageConfig scmStore = new SCMStorageConfig(conf);
    assertEquals(NodeType.SCM, scmStore.getNodeType());
    assertEquals(testClusterId, scmStore.getClusterID());
    StorageContainerManager.scmInit(conf, testClusterId);
    assertEquals(NodeType.SCM, scmStore.getNodeType());
    assertEquals(testClusterId, scmStore.getClusterID());
    assertTrue(scmStore.isSCMHAEnabled());
  }

  @Test
  public void testSCMInitializationWithHAEnabled() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, true);
    conf.set(ScmConfigKeys.OZONE_SCM_PIPELINE_CREATION_INTERVAL, "10s");
    final String path = GenericTestUtils.getTempPath(
        UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());

    final UUID clusterId = UUID.randomUUID();
    // This will initialize SCM
    StorageContainerManager.scmInit(conf, clusterId.toString());
    SCMStorageConfig scmStore = new SCMStorageConfig(conf);
    assertTrue(scmStore.isSCMHAEnabled());
    validateRatisGroupExists(conf, clusterId.toString());
  }

  @Test
  public void testSCMReinitialization() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    final String path = GenericTestUtils.getTempPath(
        UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());
    //This will set the cluster id in the version file
    MiniOzoneCluster cluster =
        MiniOzoneCluster.newBuilder(conf).setNumDatanodes(3).build();
    cluster.waitForClusterToBeReady();
    cluster.getStorageContainerManager().stop();
    try {
      final UUID clusterId = UUID.randomUUID();
      // This will initialize SCM
      StorageContainerManager.scmInit(conf, clusterId.toString());
      SCMStorageConfig scmStore = new SCMStorageConfig(conf);
      assertNotEquals(clusterId.toString(), scmStore.getClusterID());
      assertTrue(scmStore.isSCMHAEnabled());
    } finally {
      cluster.shutdown();
    }
  }

  // Unsupported Test case. Non Ratis SCM -> Ratis SCM not supported
  //@Test
  public void testSCMReinitializationWithHAUpgrade() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    final String path = GenericTestUtils.getTempPath(
        UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());
    //This will set the cluster id in the version file
    final UUID clusterId = UUID.randomUUID();
      // This will initialize SCM

    StorageContainerManager.scmInit(conf, clusterId.toString());
    SCMStorageConfig scmStore = new SCMStorageConfig(conf);
    assertEquals(clusterId.toString(), scmStore.getClusterID());
    assertFalse(scmStore.isSCMHAEnabled());

    conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, true);
    StorageContainerManager.scmInit(conf, clusterId.toString());
    scmStore = new SCMStorageConfig(conf);
    assertTrue(scmStore.isSCMHAEnabled());
    validateRatisGroupExists(conf, clusterId.toString());

  }

  @VisibleForTesting
  public static void validateRatisGroupExists(OzoneConfiguration conf,
      String clusterId) throws IOException {
    final RaftProperties properties = RatisUtil.newRaftProperties(conf);
    final RaftGroupId raftGroupId =
        SCMRatisServerImpl.buildRaftGroupId(clusterId);
    final AtomicBoolean found = new AtomicBoolean(false);
    RaftServerConfigKeys.storageDir(properties).parallelStream().forEach(
        (dir) -> Optional.ofNullable(dir.listFiles()).map(Arrays::stream)
            .orElse(Stream.empty()).filter(File::isDirectory).forEach(sub -> {
              try {
                LOG.info("{}: found a subdirectory {}", raftGroupId, sub);
                RaftGroupId groupId = null;
                try {
                  groupId = RaftGroupId.valueOf(UUID.fromString(sub.getName()));
                } catch (Exception e) {
                  LOG.info("{}: The directory {} is not a group directory;"
                      + " ignoring it. ", raftGroupId, sub.getAbsolutePath());
                }
                if (groupId != null) {
                  if (groupId.equals(raftGroupId)) {
                    LOG.info(
                        "{} : The directory {} found a group directory for "
                            + "cluster {}", raftGroupId, sub.getAbsolutePath(),
                        clusterId);
                    found.set(true);
                  }
                }
              } catch (Exception e) {
                LOG.warn(
                    raftGroupId + ": Failed to find the group directory "
                        + sub.getAbsolutePath() + ".", e);
              }
            }));
    if (!found.get()) {
      throw new IOException(
          "Could not find any ratis group with id " + raftGroupId);
    }
  }

  // Non Ratis SCM -> Ratis SCM is not supported {@see HDDS-6695}
  // Invalid Testcase
  // @Test
  public void testSCMReinitializationWithHAEnabled() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, false);
    final String path = GenericTestUtils.getTempPath(
        UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());
    //This will set the cluster id in the version file
    MiniOzoneCluster cluster =
        MiniOzoneCluster.newBuilder(conf).setNumDatanodes(3).build();
    cluster.waitForClusterToBeReady();
    try {
      final String clusterId =
          cluster.getStorageContainerManager().getClusterId();
      // validate there is no ratis group pre existing
      assertThrows(IOException.class, () -> validateRatisGroupExists(conf, clusterId));

      conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, true);
      // This will re-initialize SCM
      StorageContainerManager.scmInit(conf, clusterId);
      cluster.getStorageContainerManager().start();
      // Ratis group with cluster id exists now
      validateRatisGroupExists(conf, clusterId);
      SCMStorageConfig scmStore = new SCMStorageConfig(conf);
      assertTrue(scmStore.isSCMHAEnabled());
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  void testSCMInitializationFailure() {
    OzoneConfiguration conf = new OzoneConfiguration();
    final String path =
        GenericTestUtils.getTempPath(UUID.randomUUID().toString());
    Path scmPath = Paths.get(path, "scm-meta");
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());

    Exception e = assertThrows(SCMException.class, () -> HddsTestUtils.getScmSimple(conf));
    assertThat(e).hasMessageContaining("SCM not initialized due to storage config failure");
  }

  @Test
  public void testScmInfo() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    final String path =
        GenericTestUtils.getTempPath(UUID.randomUUID().toString());
    try {
      Path scmPath = Paths.get(path, "scm-meta");
      conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, scmPath.toString());
      SCMStorageConfig scmStore = new SCMStorageConfig(conf);
      String clusterId = UUID.randomUUID().toString();
      String scmId = UUID.randomUUID().toString();
      scmStore.setClusterId(clusterId);
      scmStore.setScmId(scmId);
      // writes the version file properties
      scmStore.initialize();
      StorageContainerManager scm = HddsTestUtils.getScmSimple(conf);
      //Reads the SCM Info from SCM instance
      ScmInfo scmInfo = scm.getClientProtocolServer().getScmInfo();
      assertEquals(clusterId, scmInfo.getClusterId());
      assertEquals(scmId, scmInfo.getScmId());

      String expectedVersion = HddsVersionInfo.HDDS_VERSION_INFO.getVersion();
      String actualVersion = scm.getSoftwareVersion();
      assertEquals(expectedVersion, actualVersion);
    } finally {
      FileUtils.deleteQuietly(new File(path));
    }
  }

  /**
   * Test datanode heartbeat well processed with a 4-layer network topology.
   */
  @Test
  public void testScmProcessDatanodeHeartbeat() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setClass(NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY,
        StaticMapping.class, DNSToSwitchMapping.class);
    StaticMapping.addNodeToRack(NetUtils.normalizeHostNames(
        Collections.singleton(HddsUtils.getHostName(conf))).get(0),
        "/rack1");

    final int datanodeNum = 3;
    MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(datanodeNum)
        .build();
    cluster.waitForClusterToBeReady();
    StorageContainerManager scm = cluster.getStorageContainerManager();

    try {
      // first sleep 10s
      Thread.sleep(10000);
      // verify datanode heartbeats are well processed
      long heartbeatCheckerIntervalMs = cluster.getConf()
          .getTimeDuration(HDDS_HEARTBEAT_INTERVAL, 1000,
              TimeUnit.MILLISECONDS);
      long start = Time.monotonicNow();
      Thread.sleep(heartbeatCheckerIntervalMs * 2);

      List<DatanodeDetails> allNodes = scm.getScmNodeManager().getAllNodes();
      assertEquals(datanodeNum, allNodes.size());
      for (DatanodeDetails node : allNodes) {
        DatanodeInfo datanodeInfo = (DatanodeInfo) scm.getScmNodeManager()
            .getNodeByUuid(node.getUuidString());
        assertThat(datanodeInfo.getLastHeartbeatTime()).isGreaterThan(start);
        assertEquals(datanodeInfo.getUuidString(),
            datanodeInfo.getNetworkName());
        assertEquals("/rack1", datanodeInfo.getNetworkLocation());
      }
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCloseContainerCommandOnRestart() throws Exception {
    int numKeys = 15;
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setTimeDuration(HDDS_CONTAINER_REPORT_INTERVAL, 1, TimeUnit.SECONDS);
    conf.setInt(ScmConfigKeys.OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 5);
    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL,
        100, TimeUnit.MILLISECONDS);
    conf.setInt(ScmConfigKeys.OZONE_SCM_PIPELINE_OWNER_CONTAINER_COUNT,
        numKeys);
    conf.setBoolean(HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);

    MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
        .setHbInterval(1000)
        .setHbProcessorInterval(3000)
        .setNumDatanodes(1)
        .build();
    cluster.waitForClusterToBeReady();
    cluster.waitForPipelineTobeReady(HddsProtos.ReplicationFactor.ONE, 30000);

    try {
      TestStorageContainerManagerHelper helper =
          new TestStorageContainerManagerHelper(cluster, conf);

      helper.createKeys(10, 4096);
      GenericTestUtils.waitFor(() ->
          cluster.getStorageContainerManager().getContainerManager()
              .getContainers() != null, 1000, 10000);

      StorageContainerManager scm = cluster.getStorageContainerManager();
      List<ContainerInfo> containers = cluster.getStorageContainerManager()
          .getContainerManager().getContainers();
      assertNotNull(containers);
      ContainerInfo selectedContainer = containers.iterator().next();

      // Stop processing HB
      scm.getDatanodeProtocolServer().stop();

      LOG.info(
          "Current Container State is {}", selectedContainer.getState());
      try {
        scm.getContainerManager().updateContainerState(selectedContainer
            .containerID(), HddsProtos.LifeCycleEvent.FINALIZE);
      } catch (SCMException ex) {
        if (selectedContainer.getState() != HddsProtos.LifeCycleState.CLOSING) {
          ex.printStackTrace();
          throw(ex);
        }
      }

      cluster.restartStorageContainerManager(false);
      scm = cluster.getStorageContainerManager();

      ReplicationManager rm = scm.getReplicationManager();

      NodeManager nodeManager = mock(NodeManager.class);
      setInternalState(rm, "nodeManager", nodeManager);

      EventPublisher publisher = mock(EventPublisher.class);
      setInternalState(rm.getLegacyReplicationManager(),
          "eventPublisher", publisher);

      UUID dnUuid = cluster.getHddsDatanodes().iterator().next()
          .getDatanodeDetails().getUuid();

      CloseContainerCommand closeContainerCommand =
          new CloseContainerCommand(selectedContainer.getContainerID(),
              selectedContainer.getPipelineID(), false);

      GenericTestUtils.waitFor(() -> {
        SCMContext scmContext
            = cluster.getStorageContainerManager().getScmContext();
        return !scmContext.isInSafeMode() && scmContext.isLeader();
      }, 1000, 25000);

      // After safe mode is off, ReplicationManager starts to run with a delay.
      Thread.sleep(5000);
      // Give ReplicationManager some time to process the containers.
      cluster.getStorageContainerManager()
          .getReplicationManager().processAll();
      Thread.sleep(5000);

      if (rm.getConfig().isLegacyEnabled()) {
        CommandForDatanode commandForDatanode = new CommandForDatanode(
            dnUuid, closeContainerCommand);
        verify(publisher).fireEvent(eq(SCMEvents.DATANODE_COMMAND), argThat(new
            CloseContainerCommandMatcher(dnUuid, commandForDatanode)));
      } else {
        verify(nodeManager).addDatanodeCommand(dnUuid, closeContainerCommand);
      }
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testContainerReportQueueWithDrop() throws Exception {
    EventQueue eventQueue = new EventQueue();
    List<BlockingQueue<SCMDatanodeHeartbeatDispatcher.ContainerReport>>
        queues = new ArrayList<>();
    for (int i = 0; i < 1; ++i) {
      queues.add(new ContainerReportQueue());
    }
    ContainerReportsProto report = ContainerReportsProto.getDefaultInstance();
    DatanodeDetails dn = DatanodeDetails.newBuilder().setUuid(UUID.randomUUID())
        .build();
    ContainerReportFromDatanode dndata
        = new ContainerReportFromDatanode(dn, report);
    ContainerReportHandler containerReportHandler =
        mock(ContainerReportHandler.class);
    doAnswer((inv) -> {
      Thread.currentThread().sleep(500);
      return null;
    }).when(containerReportHandler).onMessage(dndata, eventQueue);
    List<ThreadPoolExecutor> executors = FixedThreadPoolWithAffinityExecutor
        .initializeExecutorPool(queues);
    Map<String, FixedThreadPoolWithAffinityExecutor> reportExecutorMap
        = new ConcurrentHashMap<>();
    EventExecutor<ContainerReportFromDatanode>
        containerReportExecutors =
        new FixedThreadPoolWithAffinityExecutor<>(
            EventQueue.getExecutorName(SCMEvents.CONTAINER_REPORT,
                containerReportHandler),
            containerReportHandler, queues, eventQueue,
            ContainerReportFromDatanode.class, executors,
            reportExecutorMap);
    eventQueue.addHandler(SCMEvents.CONTAINER_REPORT, containerReportExecutors,
        containerReportHandler);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata);
    assertThat(containerReportExecutors.droppedEvents()).isGreaterThan(1);
    Thread.currentThread().sleep(1000);
    assertEquals(containerReportExecutors.droppedEvents()
            + containerReportExecutors.scheduledEvents(),
        containerReportExecutors.queuedEvents());
    containerReportExecutors.close();
  }

  @Test
  public void testContainerReportQueueTakingMoreTime() throws Exception {
    EventQueue eventQueue = new EventQueue();
    List<BlockingQueue<SCMDatanodeHeartbeatDispatcher.ContainerReport>>
        queues = new ArrayList<>();
    for (int i = 0; i < 1; ++i) {
      queues.add(new ContainerReportQueue());
    }
    Semaphore semaphore = new Semaphore(2);
    semaphore.acquire(2);
    ContainerReportHandler containerReportHandler =
        mock(ContainerReportHandler.class);
    doAnswer((inv) -> {
      Thread.currentThread().sleep(1000);
      semaphore.release(1);
      return null;
    }).when(containerReportHandler).onMessage(any(), eq(eventQueue));
    List<ThreadPoolExecutor> executors = FixedThreadPoolWithAffinityExecutor
        .initializeExecutorPool(queues);
    Map<String, FixedThreadPoolWithAffinityExecutor> reportExecutorMap
        = new ConcurrentHashMap<>();
    FixedThreadPoolWithAffinityExecutor<ContainerReportFromDatanode,
        SCMDatanodeHeartbeatDispatcher.ContainerReport>
        containerReportExecutors =
        new FixedThreadPoolWithAffinityExecutor<>(
            EventQueue.getExecutorName(SCMEvents.CONTAINER_REPORT,
                containerReportHandler),
            containerReportHandler, queues, eventQueue,
            ContainerReportFromDatanode.class, executors,
            reportExecutorMap);
    containerReportExecutors.setQueueWaitThreshold(800);
    containerReportExecutors.setExecWaitThreshold(800);
    
    eventQueue.addHandler(SCMEvents.CONTAINER_REPORT, containerReportExecutors,
        containerReportHandler);
    ContainerReportsProto report = ContainerReportsProto.getDefaultInstance();
    DatanodeDetails dn = DatanodeDetails.newBuilder().setUuid(UUID.randomUUID())
        .build();
    ContainerReportFromDatanode dndata1
        = new ContainerReportFromDatanode(dn, report);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata1);

    dn = DatanodeDetails.newBuilder().setUuid(UUID.randomUUID())
        .build();
    ContainerReportFromDatanode dndata2
        = new ContainerReportFromDatanode(dn, report);
    eventQueue.fireEvent(SCMEvents.CONTAINER_REPORT, dndata2);
    semaphore.acquire(2);
    assertThat(containerReportExecutors.longWaitInQueueEvents()).isGreaterThanOrEqualTo(1);
    assertThat(containerReportExecutors.longTimeExecutionEvents()).isGreaterThanOrEqualTo(1);
    containerReportExecutors.close();
    semaphore.release(2);
  }

  @Test
  public void testIncrementalContainerReportQueue() throws Exception {
    EventQueue eventQueue = new EventQueue();
    List<BlockingQueue<SCMDatanodeHeartbeatDispatcher.ContainerReport>>
        queues = new ArrayList<>();
    for (int i = 0; i < 1; ++i) {
      queues.add(new ContainerReportQueue());
    }
    DatanodeDetails dn = DatanodeDetails.newBuilder().setUuid(UUID.randomUUID())
        .build();
    IncrementalContainerReportProto report
        = IncrementalContainerReportProto.getDefaultInstance();
    IncrementalContainerReportFromDatanode dndata
        = new IncrementalContainerReportFromDatanode(dn, report);
    IncrementalContainerReportHandler icr =
        mock(IncrementalContainerReportHandler.class);
    doAnswer((inv) -> {
      Thread.currentThread().sleep(500);
      return null;
    }).when(icr).onMessage(dndata, eventQueue);
    List<ThreadPoolExecutor> executors = FixedThreadPoolWithAffinityExecutor
        .initializeExecutorPool(queues);
    Map<String, FixedThreadPoolWithAffinityExecutor> reportExecutorMap
        = new ConcurrentHashMap<>();
    EventExecutor<IncrementalContainerReportFromDatanode>
        containerReportExecutors =
        new FixedThreadPoolWithAffinityExecutor<>(
            EventQueue.getExecutorName(SCMEvents.INCREMENTAL_CONTAINER_REPORT,
                icr),
            icr, queues, eventQueue,
            IncrementalContainerReportFromDatanode.class, executors,
            reportExecutorMap);
    eventQueue.addHandler(SCMEvents.INCREMENTAL_CONTAINER_REPORT,
        containerReportExecutors, icr);
    eventQueue.fireEvent(SCMEvents.INCREMENTAL_CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.INCREMENTAL_CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.INCREMENTAL_CONTAINER_REPORT, dndata);
    eventQueue.fireEvent(SCMEvents.INCREMENTAL_CONTAINER_REPORT, dndata);
    assertEquals(0, containerReportExecutors.droppedEvents());
    Thread.currentThread().sleep(3000);
    assertEquals(containerReportExecutors.scheduledEvents(),
        containerReportExecutors.queuedEvents());
    containerReportExecutors.close();
  }

  @Test
  public void testNonRatisToRatis()
      throws IOException, AuthenticationException, InterruptedException,
      TimeoutException {
    final OzoneConfiguration conf = new OzoneConfiguration();
    try (MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(3)
        .build()) {
      final StorageContainerManager nonRatisSCM = cluster
          .getStorageContainerManager();
      assertNull(nonRatisSCM.getScmHAManager().getRatisServer());
      assertFalse(nonRatisSCM.getScmStorageConfig().isSCMHAEnabled());
      nonRatisSCM.stop();
      nonRatisSCM.join();

      DefaultConfigManager.clearDefaultConfigs();
      conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, true);
      StorageContainerManager.scmInit(conf, cluster.getClusterId());
      cluster.restartStorageContainerManager(false);

      final StorageContainerManager ratisSCM = cluster
          .getStorageContainerManager();
      assertNotNull(ratisSCM.getScmHAManager().getRatisServer());
      assertTrue(ratisSCM.getScmStorageConfig().isSCMHAEnabled());

    }
  }

  private void addTransactions(StorageContainerManager scm,
      DeletedBlockLog delLog,
      Map<Long, List<Long>> containerBlocksMap)
      throws IOException, TimeoutException {
    delLog.addTransactions(containerBlocksMap);
    if (SCMHAUtils.isSCMHAEnabled(scm.getConfiguration())) {
      scm.getScmHAManager().asSCMHADBTransactionBuffer().flush();
    }
  }

  private static class CloseContainerCommandMatcher
      implements ArgumentMatcher<CommandForDatanode> {

    private final CommandForDatanode cmd;
    private final UUID uuid;

    CloseContainerCommandMatcher(UUID uuid, CommandForDatanode cmd) {
      this.uuid = uuid;
      this.cmd = cmd;
    }

    @Override
    public boolean matches(CommandForDatanode cmdRight) {
      CloseContainerCommand left = (CloseContainerCommand) cmd.getCommand();
      CloseContainerCommand right =
          (CloseContainerCommand) cmdRight.getCommand();
      return cmdRight.getDatanodeId().equals(uuid)
          && left.getContainerID() == right.getContainerID()
          && left.getPipelineID().equals(right.getPipelineID())
          && left.getType() == right.getType()
          && left.getProto().equals(right.getProto());
    }
  }
}
