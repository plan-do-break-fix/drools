/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.impact.analysis.integrationtests;

import java.io.IOException;

import org.assertj.core.api.Assertions;
import org.drools.impact.analysis.graph.Graph;
import org.drools.impact.analysis.graph.GraphCollapsionHelper;
import org.drools.impact.analysis.graph.ImpactAnalysisHelper;
import org.drools.impact.analysis.graph.Link;
import org.drools.impact.analysis.graph.ModelToGraphConverter;
import org.drools.impact.analysis.graph.Node;
import org.drools.impact.analysis.graph.Node.Status;
import org.drools.impact.analysis.graph.ReactivityType;
import org.drools.impact.analysis.integrationtests.domain.Order;
import org.drools.impact.analysis.model.AnalysisModel;
import org.drools.impact.analysis.parser.ModelBuilder;
import org.drools.impact.analysis.parser.internal.ImpactAnalysisKieModule;
import org.drools.impact.analysis.parser.internal.ImpactAnalysisProject;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.ReleaseId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GraphCollapsionTest extends AbstractGraphTest {

    @Test
    public void testDrlRuleNamePrefix() {
        String str =
                "package mypkg;\n" +
                     "import " + Order.class.getCanonicalName() + ";" +
                     "\n" +
                     "rule CustomerCheck_1\n" +
                     "  when\n" +
                     "    $o : Order(customerMembershipRank > 5)\n" +
                     "  then\n" +
                     "    modify($o) {\n" +
                     "      setDiscount(1000);\n" +
                     "    }\n" +
                     "end\n" +
                     "\n" +
                     "rule CustomerCheck_2\n" +
                     "  when\n" +
                     "    $o : Order(customerAge > 60)\n" +
                     "  then\n" +
                     "    modify($o) {\n" +
                     "      setDiscount(2000);\n" +
                     "    }\n" +
                     "end\n" +
                     "\n" +
                     "rule PriceCheck_1\n" +
                     "  when\n" +
                     "    $o : Order(itemPrice < 2000, discount >= 2000)\n" +
                     "  then\n" +
                     "    modify($o) {\n" +
                     "      setStatus(\"Too much discount\");\n" +
                     "    }\n" +
                     "end\n" +
                     "\n" +
                     "rule PriceCheck_2\n" +
                     "  when\n" +
                     "    $o : Order(itemPrice > 5000)\n" +
                     "  then\n" +
                     "    modify($o) {\n" +
                     "      setStatus(\"Exclusive order\");\n" +
                     "    }\n" +
                     "end\n" +
                     "\n" +
                     "rule StatusCheck_1\n" +
                     "  when\n" +
                     "    $o : Order(status == \"Too much discount\")\n" +
                     "  then\n" +
                     "    modify($o) {\n" +
                     "      setDiscount(500);\n" +
                     "    }\n" +
                     "end\n" +
                     "\n" +
                     "rule StatusCheck_2\n" +
                     "  when\n" +
                     "    Order(status == \"Exclusive order\")\n" +
                     "  then\n" +
                     "    // Do some work...\n" +
                     "end";

        AnalysisModel analysisModel = new ModelBuilder().build(str);

        ModelToGraphConverter converter = new ModelToGraphConverter();
        Graph graph = converter.toGraph(analysisModel);

        Graph collapsedGraph = new GraphCollapsionHelper().collapseWithRuleNamePrefix(graph);


        generatePng(collapsedGraph);

        assertEquals(3, collapsedGraph.getNodeMap().size());
        Node node1 = collapsedGraph.getNodeMap().get("mypkg.CustomerCheck");
        Node node2 = collapsedGraph.getNodeMap().get("mypkg.PriceCheck");
        Node node3 = collapsedGraph.getNodeMap().get("mypkg.StatusCheck");

        assertEquals("CustomerCheck", node1.getRuleName());
        Assertions.assertThat(node1.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node1, node2, ReactivityType.POSITIVE),
                                                                                  new Link(node1, node2, ReactivityType.NEGATIVE));

        assertEquals("PriceCheck", node2.getRuleName());
        Assertions.assertThat(node2.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node2, node3, ReactivityType.POSITIVE),
                                                                                  new Link(node2, node3, ReactivityType.NEGATIVE));

        assertEquals("StatusCheck", node3.getRuleName());
        Assertions.assertThat(node3.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node3, node2, ReactivityType.NEGATIVE));

        //--- impact analysis
        // Assuming that "modify" action in PriceCheck_X is changed
        Node changedNode = collapsedGraph.getNodeMap().get("mypkg.PriceCheck"); // modify action in PriceCheck_X

        ImpactAnalysisHelper impactFilter = new ImpactAnalysisHelper();
        Graph impactedSubGraph = impactFilter.filterImpactedNodes(collapsedGraph, changedNode);

        generatePng(impactedSubGraph, "_impactedSubGraph");

        generatePng(collapsedGraph, "_impacted");

        assertNull(impactedSubGraph.getNodeMap().get("mypkg.CustomerCheck"));
        assertEquals(Status.CHANGED, impactedSubGraph.getNodeMap().get("mypkg.PriceCheck").getStatus());
        assertEquals(Status.IMPACTED, impactedSubGraph.getNodeMap().get("mypkg.StatusCheck").getStatus());
    }

    @Test
    public void testSpreadsheet() throws IOException {

        System.setProperty("drools.dump.dir", "/home/tkobayas/tmp");

        KieServices ks = KieServices.Factory.get();
        ReleaseId releaseId = ks.newReleaseId("org.drools.impact.analysis.integrationtests", "spreadsheet-test", "1.0.0");
        KieFileSystem kfs = createKieFileSystemWithClassPathResourceNames(releaseId, getClass(),
                                                                          "collapsion01.xls", "collapsion02.xls", "collapsion03.xls");

        //        Order order = new Order(1, "Guitar", 6000, 65, 5);
        //        runRule(kfs, order);

        KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll(ImpactAnalysisProject.class);
        ImpactAnalysisKieModule analysisKieModule = (ImpactAnalysisKieModule) kieBuilder.getKieModule();
        AnalysisModel analysisModel = analysisKieModule.getAnalysisModel();

        ModelToGraphConverter converter = new ModelToGraphConverter();
        Graph graph = converter.toGraph(analysisModel);

        Graph collapsedGraph = new GraphCollapsionHelper().collapseWithRuleNamePrefix(graph);

        generatePng(collapsedGraph, "_collapsed");

        assertEquals(3, collapsedGraph.getNodeMap().size());
        Node node1 = collapsedGraph.getNodeMap().get("mypkg2.CustomerCheck");
        Node node2 = collapsedGraph.getNodeMap().get("mypkg2.PriceCheck");
        Node node3 = collapsedGraph.getNodeMap().get("mypkg2.StatusCheck");

        assertEquals("CustomerCheck", node1.getRuleName());
        Assertions.assertThat(node1.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node1, node2, ReactivityType.POSITIVE),
                                                                                  new Link(node1, node2, ReactivityType.NEGATIVE));

        assertEquals("PriceCheck", node2.getRuleName());
        Assertions.assertThat(node2.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node2, node3, ReactivityType.POSITIVE),
                                                                                  new Link(node2, node3, ReactivityType.NEGATIVE));

        assertEquals("StatusCheck", node3.getRuleName());
        Assertions.assertThat(node3.getOutgoingLinks()).containsExactlyInAnyOrder(new Link(node3, node2, ReactivityType.NEGATIVE));

        //--- impact analysis
        // Assuming that "modify" action in PriceCheck_X is changed
        Node changedNode = collapsedGraph.getNodeMap().get("mypkg2.PriceCheck"); // modify action in PriceCheck_X

        ImpactAnalysisHelper impactFilter = new ImpactAnalysisHelper();
        Graph impactedSubGraph = impactFilter.filterImpactedNodes(collapsedGraph, changedNode);

        generatePng(impactedSubGraph, "_impactedSubGraph");

        generatePng(collapsedGraph, "_impacted");

        assertNull(impactedSubGraph.getNodeMap().get("mypkg2.CustomerCheck"));
        assertEquals(Status.CHANGED, impactedSubGraph.getNodeMap().get("mypkg2.PriceCheck").getStatus());
        assertEquals(Status.IMPACTED, impactedSubGraph.getNodeMap().get("mypkg2.StatusCheck").getStatus());
    }

}
