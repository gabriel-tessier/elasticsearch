/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.equalTo;

@ClusterScope(scope=Scope.TEST, numNodes=0)
public class ZenUnicastDiscoveryTestsMinimumMasterNodes extends ElasticsearchIntegrationTest {

    @Test
    // Without the 'include temporalResponses responses to nodesToConnect' improvement in UnicastZenPing#sendPings this
    // test fails, because 2 nodes elect themselves as master and the health request times out b/c waiting_for_nodes=3
    // can't be satisfied.
    public void testUnicastDiscovery() throws Exception {
        final Settings settings = ImmutableSettings.settingsBuilder()
                .put("discovery.zen.ping.multicast.enabled", false)
                .put("discovery.zen.minimum_master_nodes", 2)
                .put("discovery.zen.ping.unicast.hosts", "localhost")
                .put("transport.tcp.port", "25400-25500") // Need to use custom tcp port range otherwise we collide with the shared cluster
                .build();

        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicArray<String> nodes = new AtomicArray<>(3);
        Runnable r1 = new Runnable() {

            @Override
            public void run() {
                logger.info("--> start first node");
                nodes.set(0, cluster().startNode(settings));
                latch.countDown();
            }
        };
        new Thread(r1).start();

        sleep(between(500, 3000));
        Runnable r2 = new Runnable() {

            @Override
            public void run() {
                logger.info("--> start second node");
                nodes.set(1, cluster().startNode(settings));
                latch.countDown();
            }
        };
        new Thread(r2).start();


        sleep(between(500, 3000));
        Runnable r3 = new Runnable() {

            @Override
            public void run() {
                logger.info("--> start third node");
                nodes.set(2, cluster().startNode(settings));
                latch.countDown();
            }
        };
        new Thread(r3).start();
        latch.await();

        ClusterHealthResponse clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNodes("3").execute().actionGet();
        assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));

        DiscoveryNode masterDiscoNode = null;
        for (String node : nodes.toArray(new String[3])) {
            ClusterState state = cluster().client(node).admin().cluster().prepareState().setLocal(true).execute().actionGet().getState();
            assertThat(state.nodes().size(), equalTo(3));
            if (masterDiscoNode == null) {
                masterDiscoNode = state.nodes().masterNode();
            } else {
                assertThat(masterDiscoNode.equals(state.nodes().masterNode()), equalTo(true));
            }
        }
    }
}