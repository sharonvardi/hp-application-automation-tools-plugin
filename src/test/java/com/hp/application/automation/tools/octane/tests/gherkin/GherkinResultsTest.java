/*
 *     Copyright 2017 Hewlett-Packard Development Company, L.P.
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.hp.application.automation.tools.octane.tests.gherkin;

import com.hp.application.automation.tools.octane.ResultQueue;
import com.hp.application.automation.tools.octane.actions.cucumber.CucumberTestResultsActionPublisher;
import com.hp.application.automation.tools.octane.tests.*;
import hudson.matrix.*;
import hudson.maven.MavenModuleSet;
import hudson.model.*;
import hudson.tasks.Maven;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created by franksha on 05/01/2017.
 */
@SuppressWarnings({"squid:S2699","squid:S3658","squid:S2259","squid:S1872","squid:S2925","squid:S109","squid:S1607","squid:S2701","squid:S2698"})
public class GherkinResultsTest {

    @ClassRule
    public static final JenkinsRule rule = new JenkinsRule();
    private static String mavenName;

    private TestQueue queue;

    private static Set<String> tests = new HashSet<>();
    static {
        tests.add("My Amazing Feature");
        tests.add("My Fancy Feature");
    }

    @BeforeClass
    public static void prepareClass() throws Exception {
        rule.jenkins.setNumExecutors(10);
        Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven3();
        mavenName = mavenInstallation.getName();
    }

    @Before
    public void prepareTest() {
        TestListener testListener = ExtensionUtil.getInstance(rule, TestListener.class);
        queue = new TestQueue();
        testListener._setTestResultQueue(queue);
    }

    @Test
    public void testGherkinResultsDirectlyOnWorkspace() throws Exception {
        gherkinResults("*Gherkin*.xml", true);
    }

    @Test
    public void testGherkinResultsDirectlyOnWorkspaceEmptyGlob() throws Exception {
        gherkinResults("", true);
    }

    @Test
    public void testGherkinResultsDirectlyOnWorkspaceNegative() throws Exception {
        gherkinResults("abcd.xml", false);
    }

    @Test
    public void testGherkinResultsInSubFolder() throws Exception {
        gherkinResultsInSubFolder("subFolder/*Gherkin*.xml", true);
    }

    @Test
    public void testGherkinResultsInSubFolderEmptyGlob() throws Exception {
        gherkinResultsInSubFolder("", true);
    }
    @Test
    public void testGherkinResultsInSubFolderNegative() throws Exception {
        gherkinResultsInSubFolder("*Gherkin*.xml", false);
    }

    @Test
    public void testGherkinResultsDirectlyOnWorkspaceLegacy() throws Exception {
        gherkinResultsLegacy("*Gherkin*.xml", true);
    }
    @Test
    public void testGherkinResultsDirectlyOnWorkspaceLegacyEmptyGlob() throws Exception {
        gherkinResultsLegacy("", true);
    }

    @Test
    public void testGherkinResultsDirectlyOnWorkspaceLegacyNegative() throws Exception {
        gherkinResultsLegacy("abcd.xml", false);
    }

    @Test
    public void testGherkinResultsInSubFolderLegacy() throws Exception {
        gherkinResultsLegacyWithSubFolder("subFolder/*Gherkin*.xml", true);
    }

    @Test
    public void testGherkinResultsInSubFolderLegacyEmptyGlob() throws Exception {
        gherkinResultsLegacyWithSubFolder("", true);
    }

    @Test
    public void testGherkinResultsInSubFolderLegacyNegative() throws Exception {
        gherkinResultsLegacyWithSubFolder("*Gherkin*.xml", false);
    }

    @Test
    public void testGherkinResultsMatrixProject() throws Exception {
        String projectName = "root-job-" + UUID.randomUUID().toString();
        MatrixProject matrixProject = rule.createProject(MatrixProject.class, projectName);
        matrixProject.setAxes(new AxisList(new Axis("osType", "Linux", "Windows")));

        matrixProject.getBuildersList().add(new Maven("-s settings.xml clean test", mavenName, null, null, "-Dmaven.test.failure.ignore=true"));
        matrixProject.getPublishersList().add(new CucumberTestResultsActionPublisher(""));
        matrixProject.setScm(new CopyResourceSCM("/helloCucumberWorld"));

        MatrixBuild build = (MatrixBuild) TestUtils.runAndCheckBuild(matrixProject);
        for (MatrixRun run : build.getExactRuns()) {
            assertTestResultsEqual(tests, new File(run.getRootDir(), "mqmTests.xml"));
        }
        Assert.assertEquals(new HashSet<>(Arrays.asList(projectName + "/osType=Windows#1", projectName + "/osType=Linux#1")), getQueuedItems());
        Assert.assertFalse(new File(build.getRootDir(), "mqmTests.xml").exists());
    }

    @Test
    public void testGherkinResultsWrongFile() throws Exception {
        gherkinResults("pom.xml", false);
    }

    @Test
    public void testGherkinResultsWrongLongFile() throws Exception {
        gherkinResults("settings.xml", false);
    }

    private void gherkinResults(String glob, boolean buildShouldSucceed) throws Exception {
        String projectName = "root-job-" + UUID.randomUUID().toString();
        FreeStyleProject project = rule.createFreeStyleProject(projectName);

        project.getBuildersList().add(new Maven("-s settings.xml clean test", mavenName, null, null, "-Dmaven.test.failure.ignore=true"));
        project.setScm(new CopyResourceSCM("/helloCucumberWorld"));

        project.getPublishersList().add(new CucumberTestResultsActionPublisher(glob));

        assertProject(project, buildShouldSucceed);
    }

    private void gherkinResultsInSubFolder(String glob, boolean buildShouldSucceed) throws Exception {
        String projectName = "root-job-" + UUID.randomUUID().toString();
        FreeStyleProject project = rule.createFreeStyleProject(projectName);

        project.getBuildersList().add(new Maven("-s subFolder/settings.xml clean test", mavenName, "subFolder/pom.xml", null, "-Dmaven.test.failure.ignore=true"));
        project.setScm(new CopyResourceSCM("helloCucumberWorld", "subFolder"));

        project.getPublishersList().add(new CucumberTestResultsActionPublisher(glob));

        assertProject(project, buildShouldSucceed);
    }

    private void gherkinResultsLegacy(String glob, boolean buildShouldSucceed) throws Exception {
        MavenModuleSet project = prepareLegacyProject(false);
        project.getPublishersList().add(new CucumberTestResultsActionPublisher(glob));
        assertProject(project, buildShouldSucceed);
    }

    private void gherkinResultsLegacyWithSubFolder(String glob, boolean buildShouldSucceed) throws Exception {
        MavenModuleSet project = prepareLegacyProject(true);
        project.getPublishersList().add(new CucumberTestResultsActionPublisher(glob));
        assertProject(project, buildShouldSucceed);
    }

    private MavenModuleSet prepareLegacyProject(boolean subfolder) throws IOException {
        String projectName = "root-job-" + UUID.randomUUID().toString();
        MavenModuleSet project = rule.createProject(MavenModuleSet.class, projectName);
        project.runHeadless();

        project.setMaven(mavenName);
        project.setGoals("-s settings.xml clean test -Dmaven.test.failure.ignore=true");
        if(subfolder) {
            project.setRootPOM("subFolder/pom.xml");
            project.setScm(new CopyResourceSCM("/helloCucumberWorld", "subFolder"));
        } else {
            project.setScm(new CopyResourceSCM("/helloCucumberWorld"));
        }
        return project;
    }

    private void assertProject(AbstractProject project, boolean buildShouldSucceed) throws Exception {
        if(buildShouldSucceed) {
            AbstractBuild build = TestUtils.runAndCheckBuild(project);
            assertTestResultsEqual(tests, new File(build.getRootDir(), "mqmTests.xml"));
            Assert.assertEquals(Collections.singleton(project.getName() + "#1"), getQueuedItems());
        } else {
            AbstractBuild build = (AbstractBuild) project.scheduleBuild2(0).get();
            Assert.assertEquals("Build should fail", Result.FAILURE, build.getResult());
            Assert.assertEquals("Expects empty queue", 0, getQueuedItems().size());
        }
    }

    private void assertTestResultsEqual(Set<String> expected, File actual) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document actualDoc = dBuilder.parse(actual);

        NodeList actualTests = actualDoc.getElementsByTagName("gherkin_test_run");
        Assert.assertEquals("Number of tests should be equal", expected.size(), actualTests.getLength());

        NodeList automatedTests = actualDoc.getElementsByTagName("test_run");
        Assert.assertEquals("There should be no automated tests", 0, automatedTests.getLength());

        int i=0;
        for (String expectedName : expected) {
            Node actualTest = actualTests.item(i++);
            Assert.assertEquals("Test name should be equal", expectedName, getAttr(actualTest, "name"));
            Assert.assertEquals("Test status should be equal", "Passed", getAttr(actualTest, "status"));
        }
    }

    private String getAttr(Node node, String attrName) {
        return node.getAttributes().getNamedItem(attrName).getNodeValue();
    }

    private Set<String> getQueuedItems() {
        Set<String> ret = new HashSet<>();
        ResultQueue.QueueItem item;
        while ((item = queue.peekFirst()) != null) {
            ret.add(item.getProjectName() + "#" + item.getBuildNumber());
            queue.remove();
        }
        return ret;
    }
}
