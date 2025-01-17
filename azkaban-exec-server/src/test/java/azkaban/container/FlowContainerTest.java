/*
 * Copyright 2021 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package azkaban.container;

import azkaban.AzkabanCommonModule;
import azkaban.common.ExecJettyServerModule;
import azkaban.db.DatabaseOperator;
import azkaban.execapp.AzkabanExecutorServerTest;
import azkaban.execapp.FlowRunner;
import azkaban.execapp.FlowRunner.FlowRunnerProxy;
import azkaban.execapp.event.JobCallbackManager;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutorLoader;
import azkaban.project.ProjectFileHandler;
import azkaban.project.ProjectLoader;
import azkaban.spi.AzkabanEventReporter;
import azkaban.test.Utils;
import azkaban.utils.Props;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.mockito.Mockito;

import static azkaban.Constants.ConfigurationKeys.*;
import static azkaban.ServiceProvider.*;
import static azkaban.container.FlowContainer.*;
import static azkaban.utils.TestUtils.*;
import static java.util.Objects.*;
import static org.mockito.Mockito.*;

public class FlowContainerTest {

  private static final Logger logger = Logger.getLogger(FlowContainerTest.class);
  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  public static final String AZKABAN_LOCAL_TEST_STORAGE = "AZKABAN_LOCAL_TEST_STORAGE";
  public static final String AZKABAN_DB_SQL_PATH = "azkaban-db/src/main/sql";

  public static final Props props = new Props();
  private static DatabaseOperator dbOperator;

  private ExecutorLoader executorLoader;
  private ProjectLoader projectLoader;
  private AzkabanEventReporter eventReporter;
  private ExecJettyServerModule jettyServer;
  private AzkabanCommonModule commonModule;
  private FlowContainer flowContainer;

  private static Path azkabanRoot;

  @BeforeClass
  public static void setUp() throws Exception {
    props.put("database.type", "h2");
    props.put("h2.path", "./h2");
    props.put(AZKABAN_EVENT_REPORTING_ENABLED, 0);
    dbOperator = Utils.initTestDB();
    SERVICE_PROVIDER.unsetInjector();
    final URL resource = AzkabanExecutorServerTest.class.getClassLoader().getResource("test.file");
    final String dummyResourcePath = requireNonNull(resource).getPath();
    final Path resources = Paths.get(dummyResourcePath).getParent();
    azkabanRoot = resources.getParent().getParent().getParent().getParent();
    FlowContainer.setInjector(props);
  }

  @AfterClass
  public static void destroyDB() {
    try {
      dbOperator.update("DROP ALL OBJECTS");
      dbOperator.update("SHUTDOWN");
    } catch (final SQLException e) {
      logger.error("destroy DB failed at the end of test suite: ", e);
    }
  }

  @Before
  public void setup() throws Exception {
    this.executorLoader = mock(ExecutorLoader.class);
    this.projectLoader = mock(ProjectLoader.class);
  }

  @After
  public void destroy() {
    if (this.flowContainer == null) {
      return;
    }
    try {
      this.flowContainer.closeMBeans();
      this.flowContainer = null;
    } catch (final Exception e) {
      logger.error("destroy failed: ", e);
    }
  }

  private void startFlowContainer() throws IOException {
    final ExecutableFlow execFlow = createTestExecutableFlowFromYaml("basicflowyamltest", "basic_flow");
    execFlow.setExecutionId(1);
    final ProjectFileHandler handler = new ProjectFileHandler(1, 1, 1, "testUser", "zip", "test.zip",
        1, null, null, null, "111.111.111.111");
    when(this.projectLoader.fetchProjectMetaData(anyInt(), anyInt())).thenReturn(handler);

    this.flowContainer = SERVICE_PROVIDER.getInstance(FlowContainer.class);
    this.flowContainer.start(props);
  }

  /**
   * FIXME: This test is incomplete for now as there is an expected merge conflict which will
   * change interface with submitFlow. Once new code is merged in FlowContainer,
   * this test will need to be completed by invoking a flow.
   * @throws Exception
   */
  @Test
  public void testExecSimple() throws Exception {
    startFlowContainer();
  }

  @Test
  public void testDeleteFile() throws Exception {
    // Create a file
    final Path filePath = Files.createFile(Paths.get("abc.txt"));
    // Create symlink to the file
    final Path symLink1 = Paths.get("link1");
    Files.createSymbolicLink(symLink1, filePath);
    // Create symlink to the symlink created above
    final Path symLink2 = Paths.get("link2");
    Files.createSymbolicLink(symLink2, symLink1);
    // A sanity check
    assert((Files.exists(symLink2) && Files.exists(symLink1) && Files.exists(filePath)));
    // Must delete all the links and files as top level symlink is provided.
    deleteSymlinkedFile(symLink2);
    // Make sure none of the symlinks or files exist.
    assert(!(Files.exists(symLink2) || Files.exists(symLink1) || Files.exists(filePath)));
  }

  /**
   * Test if Callback Manager is created when Flow Container starts
   */
  @Test
  public void testCallBackManager() throws Exception {
    // Enable jobcallback explicitly.
    props.put("azkaban.executor.jobcallback.enabled", "true");
    startFlowContainer();
    // The callback manager must be set.
    assert JobCallbackManager.isInitialized();

    // Get the instance
    final JobCallbackManager jobCallbackManager = JobCallbackManager.getInstance();
    assert jobCallbackManager != null;
  }

  @Test
  public void testSetResourceUtilization() {
    FlowContainer flowContainerMock = mock(FlowContainer.class);
    FlowRunner flowRunnerMock = mock(FlowRunner.class);
    FlowRunnerProxy flowRunnerProxyMock = mock(FlowRunnerProxy.class);

    Mockito.doReturn(flowRunnerProxyMock).when(flowRunnerMock).getProxy();
    Mockito.doCallRealMethod().when(flowContainerMock).setResourceUtilization();
    Mockito.doCallRealMethod().when(flowContainerMock).setFlowRunner(flowRunnerMock);

    flowContainerMock.setFlowRunner(flowRunnerMock);
    flowContainerMock.setResourceUtilization();

    // CPU_REQUEST and MEMORY_REQUEST ENV variables are not set
    Mockito.verify(flowRunnerProxyMock, Mockito.times(0)).setCpuUtilization(anyDouble());
    Mockito.verify(flowRunnerProxyMock, Mockito.times(0)).setMemoryUtilization(anyLong());

    // Set CPU_REQUEST ENV variable
    environmentVariables.set("CPU_REQUEST", "500m");

    flowContainerMock.setResourceUtilization();
    Mockito.verify(flowRunnerProxyMock, Mockito.times(1)).setCpuUtilization(0.5d);
    Mockito.verify(flowRunnerProxyMock, Mockito.times(0)).setMemoryUtilization(524288000L);

    // Set MEMORY_REQUEST ENV variable
    environmentVariables.set("MEMORY_REQUEST", "500Mi");
    flowContainerMock.setResourceUtilization();
    // Times will be two as it already executed once before
    Mockito.verify(flowRunnerProxyMock, Mockito.times(2)).setCpuUtilization(0.5d);
    Mockito.verify(flowRunnerProxyMock, Mockito.times(1)).setMemoryUtilization(524288000L);
  }
}
