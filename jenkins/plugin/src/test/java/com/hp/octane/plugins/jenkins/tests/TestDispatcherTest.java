package com.hp.octane.plugins.jenkins.tests;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.hp.mqm.client.MqmRestClient;
import com.hp.mqm.client.exception.*;
import com.hp.octane.plugins.jenkins.ExtensionUtil;
import com.hp.octane.plugins.jenkins.client.JenkinsMqmRestClientFactory;
import com.hp.octane.plugins.jenkins.client.RetryModel;
import com.hp.octane.plugins.jenkins.client.TestEventPublisher;
import com.hp.octane.plugins.jenkins.identity.ServerIdentity;
import hudson.FilePath;
import hudson.matrix.*;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Maven;
import hudson.tasks.junit.JUnitResultArchiver;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class TestDispatcherTest {

	private TestQueue queue;
	private TestDispatcher testDispatcher;
	private JenkinsMqmRestClientFactory clientFactory;
	private MqmRestClient restClient;
	private TestEventPublisher testEventPublisher;

	@Rule
	final public JenkinsRule rule = new JenkinsRule();

	private FreeStyleProject project;

	@BeforeClass
	public static void initClass() {
		System.setProperty("MQM.TestDispatcher.Period", "1000");
	}

	@Before
	public void init() throws Exception {
		restClient = Mockito.mock(MqmRestClient.class);
		clientFactory = Mockito.mock(JenkinsMqmRestClientFactory.class);
		Mockito.when(clientFactory.obtain(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(restClient);
		testDispatcher = ExtensionUtil.getInstance(rule, TestDispatcher.class);
		testDispatcher._setMqmRestClientFactory(clientFactory);
		queue = new TestQueue();
		testDispatcher._setTestResultQueue(queue);
		queue.waitForTicks(1); // needed to avoid occasional interaction with the client we just overrode (race condition)

		testEventPublisher = new TestEventPublisher();
		RetryModel retryModel = new RetryModel(testEventPublisher);
		testDispatcher._setRetryModel(retryModel);
		testDispatcher._setEventPublisher(testEventPublisher);

		project = rule.createFreeStyleProject("TestDispatcher");
		Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven3();

		project.getBuildersList().add(new Maven("-s settings.xml install", mavenInstallation.getName(), null, null, "-Dmaven.test.failure.ignore=true"));
		project.getPublishersList().add(new JUnitResultArchiver("**/target/surefire-reports/*.xml"));
		project.setScm(new CopyResourceSCM("/helloWorldRoot"));

		// server needs to be configured in order for the processing to happen
		HtmlPage configPage = rule.createWebClient().goTo("configure");
		HtmlForm form = configPage.getFormByName("config");
		form.getInputByName("_.uiLocation").setValueAttribute("http://localhost:8008/ui/?p=1001/1002");
		form.getInputByName("_.username").setValueAttribute("username");
		form.getInputByName("_.secretPassword").setValueAttribute("password");
		rule.submit(form);
	}

	@Test
	public void testDispatcher() throws Exception {
		mockRestClient(restClient, true, true, true);
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(5);
		verifyRestClient(restClient, build, true);
		verifyAudit(build, true);

		mockRestClient(restClient, true, true, true);
		FreeStyleBuild build2 = executeBuild();
		queue.waitForTicks(5);
		verifyRestClient(restClient, build2, true);
		verifyAudit(build2, true);
		Assert.assertEquals(0, queue.size());
	}

	@Test
	public void testDispatcherBatch() throws Exception {
		mockRestClient(restClient, true, true, true);
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		FreeStyleBuild build2 = project.scheduleBuild2(0).get();
		FreeStyleBuild build3 = project.scheduleBuild2(0).get();
		queue.add(Arrays.asList(build, build2, build3));
		queue.waitForTicks(10);

		Mockito.verify(restClient).validateConfiguration();
		Mockito.verify(restClient, Mockito.atMost(3)).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		Mockito.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		Mockito.verify(restClient).postTestResult(new File(build2.getRootDir(), "mqmTests.xml"), false);
		Mockito.verify(restClient).postTestResult(new File(build3.getRootDir(), "mqmTests.xml"), false);
		Mockito.verifyNoMoreInteractions(restClient);
		Assert.assertEquals(0, queue.size());

		verifyAudit(build, true);
		verifyAudit(build2, true);
		verifyAudit(build3, true);
	}

	@Test
	public void testDispatcherLoginFailure() throws Exception {
		mockRestClient(restClient, false, true, true);
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(5);

		verifyRestClient(restClient, build, false);
		verifyAudit(build);
		Mockito.reset(restClient);

		executeBuild();
		queue.waitForTicks(5);

		// in quiet period
		Mockito.verifyNoMoreInteractions(restClient);
		Assert.assertEquals(2, queue.size());
	}

	@Test
	public void testDispatcherSessionFailure() throws Exception {
		mockRestClient(restClient, true, false, true);
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(5);

		verifyRestClient(restClient, build, false);
		verifyAudit(build);
		Mockito.reset(restClient);

		executeBuild();
		queue.waitForTicks(5);

		// in quiet period
		Mockito.verifyNoMoreInteractions(restClient);
		Assert.assertEquals(2, queue.size());
	}

	@Test
	public void testDispatcherSharedSpaceFailure() throws Exception {
		mockRestClient(restClient, true, true, false);
		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(5);

		verifyRestClient(restClient, build, false);
		verifyAudit(build);
		Mockito.reset(restClient);

		executeBuild();
		queue.waitForTicks(5);

		// in quiet period
		Mockito.verifyNoMoreInteractions(restClient);
		Assert.assertEquals(2, queue.size());
	}

	@Test
	public void testDispatcherBodyFailure() throws Exception {
		// body post fails for the first time, succeeds afterwards

		Mockito.doNothing().when(restClient).validateConfiguration();
		Mockito.when(restClient.isTestResultRelevant(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
		Mockito.doThrow(new RequestException("fails")).doReturn(1l).when(restClient).postTestResult(Mockito.argThat(new MqmTestsFileMatcher()), Mockito.eq(false));
		InOrder order = Mockito.inOrder(restClient);

		FreeStyleBuild build = executeBuild();
		queue.waitForTicks(5);

		order.verify(restClient).validateConfiguration();
		order.verify(restClient).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		order.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);

		Mockito.verify(restClient, Mockito.times(2)).validateConfiguration();
		Mockito.verify(restClient, Mockito.times(2)).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		Mockito.verify(restClient, Mockito.times(2)).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		Mockito.verifyNoMoreInteractions(restClient);
		verifyAudit(build, false, true);

		Assert.assertEquals(0, queue.size());
		Assert.assertEquals(0, queue.getDiscards());

		// body post fails for two consecutive times

		Mockito.reset(restClient);
		Mockito.doNothing().when(restClient).validateConfiguration();
		Mockito.when(restClient.isTestResultRelevant(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
		Mockito.doThrow(new RequestException("fails")).doThrow(new RequestException("fails")).when(restClient).postTestResult(Mockito.argThat(new MqmTestsFileMatcher()), Mockito.eq(false));

		order = Mockito.inOrder(restClient);

		build = executeBuild();
		queue.waitForTicks(5);

		order.verify(restClient).validateConfiguration();
		order.verify(restClient).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		order.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		order.verify(restClient).validateConfiguration();
		order.verify(restClient).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		order.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);

		Mockito.verify(restClient, Mockito.times(2)).validateConfiguration();
		Mockito.verify(restClient, Mockito.times(2)).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		Mockito.verify(restClient, Mockito.times(2)).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		Mockito.verifyNoMoreInteractions(restClient);
		verifyAudit(build, false, false);

		Assert.assertEquals(0, queue.size());
		Assert.assertEquals(1, queue.getDiscards());
	}

	@Test
	public void testDispatcherSuspended() throws Exception {
		testEventPublisher.setSuspended(true);

		executeBuild();

		queue.waitForTicks(2);

		// events suspended
		Mockito.verifyNoMoreInteractions(restClient);
		Assert.assertEquals(1, queue.size());
	}

	@Test
	public void testDispatchMatrixBuild() throws Exception {
		MatrixProject matrixProject = rule.createProject(MatrixProject.class, "TestDispatcherMatrix");
		matrixProject.setAxes(new AxisList(new Axis("OS", "Linux", "Windows")));

		Maven.MavenInstallation mavenInstallation = ToolInstallations.configureMaven3();

		matrixProject.getBuildersList().add(new Maven("-s settings.xml install", mavenInstallation.getName(), null, null, "-Dmaven.test.failure.ignore=true"));
		matrixProject.getPublishersList().add(new JUnitResultArchiver("**/target/surefire-reports/*.xml"));
		matrixProject.setScm(new CopyResourceSCM("/helloWorldRoot"));

		mockRestClient(restClient, true, true, true);
		MatrixBuild matrixBuild = matrixProject.scheduleBuild2(0).get();
		for (MatrixRun run : matrixBuild.getExactRuns()) {
			queue.add("TestDispatcherMatrix/" + run.getParent().getName(), run.getNumber());
		}
		queue.waitForTicks(5);
		Mockito.verify(restClient).validateConfiguration();
		for (MatrixRun run : matrixBuild.getExactRuns()) {
			Mockito.verify(restClient, Mockito.atLeast(2)).isTestResultRelevant(ServerIdentity.getIdentity(), run.getProject().getParent().getName());
			Mockito.verify(restClient).postTestResult(new File(run.getRootDir(), "mqmTests.xml"), false);
			verifyAudit(run, true);
		}
		Mockito.verifyNoMoreInteractions(restClient);

		Assert.assertEquals(0, queue.size());
	}

	@Test
	public void testDispatcherTemporarilyUnavailable() throws Exception {
		Mockito.reset(restClient);
		Mockito.doReturn(1l)
				.doThrow(new TemporarilyUnavailableException("Server busy"))
				.doThrow(new TemporarilyUnavailableException("Server busy"))
				.doThrow(new TemporarilyUnavailableException("Server busy"))
				.doThrow(new TemporarilyUnavailableException("Server busy"))
				.doThrow(new TemporarilyUnavailableException("Server busy"))
				.doReturn(1l)
				.when(restClient).postTestResult(Mockito.argThat(new MqmTestsFileMatcher()), Mockito.eq(false));
		Mockito.when(restClient.isTestResultRelevant(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
		FreeStyleBuild build = executeBuild();
		FreeStyleBuild build2 = executeBuild();
		queue.waitForTicks(12);
		Mockito.verify(restClient, Mockito.atMost(7)).validateConfiguration();
		Mockito.verify(restClient, Mockito.times(7)).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
		Mockito.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		Mockito.verify(restClient, Mockito.times(6)).postTestResult(new File(build2.getRootDir(), "mqmTests.xml"), false);
		Mockito.verifyNoMoreInteractions(restClient);
		verifyAudit(build, true);
		verifyAudit(true, build2, false, false, false, false, false, true);
		Assert.assertEquals(0, queue.size());
	}

	private FreeStyleBuild executeBuild() throws ExecutionException, InterruptedException {
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		queue.add(build.getProject().getName(), build.getNumber());
		return build;
	}

	private void verifyAudit(AbstractBuild build, boolean... statuses) throws IOException, InterruptedException {
		verifyAudit(false, build, statuses);
	}

	private void verifyAudit(boolean unavailableIfFailed, AbstractBuild build, boolean... statuses) throws IOException, InterruptedException {
		FilePath auditFile = new FilePath(new File(build.getRootDir(), TestDispatcher.TEST_AUDIT_FILE));
		JSONArray audits;
		if (statuses.length > 0) {
			Assert.assertTrue(auditFile.exists());
			InputStream is = auditFile.read();
			audits = JSONArray.fromObject(IOUtils.toString(is, "UTF-8"));
			IOUtils.closeQuietly(is);
		} else {
			Assert.assertFalse(auditFile.exists());
			audits = new JSONArray();
		}
		Assert.assertEquals(statuses.length, audits.size());
		for (int i = 0; i < statuses.length; i++) {
			JSONObject audit = audits.getJSONObject(i);
			Assert.assertEquals("http://localhost:8008", audit.getString("location"));
			Assert.assertEquals("1001", audit.getString("sharedSpace"));
			Assert.assertEquals(statuses[i], audit.getBoolean("pushed"));
			if (statuses[i]) {
				Assert.assertEquals(1l, audit.getLong("id"));
			}
			if (!statuses[i] && unavailableIfFailed) {
				Assert.assertTrue(audit.getBoolean("temporarilyUnavailable"));
			} else {
				Assert.assertFalse(audit.containsKey("temporarilyUnavailable"));
			}
			Assert.assertNotNull(audit.getString("date"));
		}
	}

	private void mockRestClient(MqmRestClient restClient, boolean login, boolean session, boolean sharedSpace) throws IOException {
		Mockito.reset(restClient);
		Mockito.when(restClient.isTestResultRelevant(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
		if (!login) {
			Mockito.doThrow(new AuthenticationException()).when(restClient).validateConfiguration();
		} else if (!session) {
			Mockito.doThrow(new SessionCreationException()).when(restClient).validateConfiguration();
		} else if (!sharedSpace) {
			Mockito.doThrow(new SharedSpaceNotExistException()).when(restClient).validateConfiguration();
		} else {
			Mockito.doReturn(1l).when(restClient).postTestResult(Mockito.argThat(new MqmTestsFileMatcher()), Mockito.eq(false));
		}
	}

	private void verifyRestClient(MqmRestClient restClient, AbstractBuild build, boolean body) throws IOException {
		Mockito.verify(restClient).validateConfiguration();

		if (body) {
			Mockito.verify(restClient).isTestResultRelevant(ServerIdentity.getIdentity(), build.getProject().getName());
			Mockito.verify(restClient).postTestResult(new File(build.getRootDir(), "mqmTests.xml"), false);
		}

		Mockito.verifyNoMoreInteractions(restClient);
	}

	private static class MqmTestsFileMatcher extends BaseMatcher<File> {
		@Override
		public boolean matches(Object o) {
			return o instanceof File && ((File) o).getName().endsWith("mqmTests.xml");
		}

		@Override
		public void describeTo(Description description) {
		}
	}
}
