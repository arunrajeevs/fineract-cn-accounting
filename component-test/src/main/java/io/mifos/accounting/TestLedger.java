/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.accounting;

import io.mifos.anubis.test.v1.TenantApplicationSecurityEnvironmentTestRule;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.core.test.fixture.TenantDataStoreContextTestRule;
import io.mifos.core.test.fixture.cassandra.CassandraInitializer;
import io.mifos.core.test.fixture.mariadb.MariaDBInitializer;
import io.mifos.core.test.listener.EnableEventRecording;
import io.mifos.core.test.listener.EventRecorder;
import io.mifos.accounting.api.v1.EventConstants;
import io.mifos.accounting.api.v1.client.*;
import io.mifos.accounting.api.v1.domain.Account;
import io.mifos.accounting.api.v1.domain.Ledger;
import io.mifos.accounting.service.AccountingServiceConfiguration;
import io.mifos.accounting.util.AccountGenerator;
import io.mifos.accounting.util.LedgerGenerator;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class TestLedger {

  private static final String APP_NAME = "accounting-v1";
  private static final String TEST_USER = "setna";

  private final static TestEnvironment testEnvironment = new TestEnvironment(APP_NAME);
  private final static CassandraInitializer cassandraInitializer = new CassandraInitializer();
  private final static MariaDBInitializer mariaDBInitializer = new MariaDBInitializer();
  private final static TenantDataStoreContextTestRule tenantDataStoreContext = TenantDataStoreContextTestRule.forRandomTenantName(cassandraInitializer, mariaDBInitializer);

  @ClassRule
  public static TestRule orderClassRules = RuleChain
          .outerRule(testEnvironment)
          .around(cassandraInitializer)
          .around(mariaDBInitializer)
          .around(tenantDataStoreContext);

  @Rule
  public final TenantApplicationSecurityEnvironmentTestRule tenantApplicationSecurityEnvironment
          = new TenantApplicationSecurityEnvironmentTestRule(testEnvironment, this::waitForInitialize);

  @Autowired
  private AccountingService testSubject;
  @Autowired
  private Logger logger;
  @SuppressWarnings("SpringJavaAutowiringInspection")
  @Autowired
  private EventRecorder eventRecorder;

  private AutoUserContext autoUserContext;

  public TestLedger() {
    super();
  }

  @Before
  public void prepTest() throws Exception {
    this.logger.error("Prepare test.");
    this.autoUserContext = this.tenantApplicationSecurityEnvironment.createAutoUserContext(TestLedger.TEST_USER);
  }

  @After
  public void cleanTest() throws Exception {
    this.logger.error("Clean up test.");
    this.autoUserContext.close();
  }

  public boolean waitForInitialize() {
    try {
      return this.eventRecorder.wait(EventConstants.INITIALIZE, "1");
    } catch (final InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void shouldCreateLedger() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    Assert.assertTrue(this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier()));
  }

  @Test
  public void shouldNotCreateLedgerAlreadyExists() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier());

    try {
      this.testSubject.createLedger(ledger);
      Assert.fail();
    } catch (final LedgerAlreadyExistsException ex) {
      // do nothing, expected
    }

  }

  @Test
  public void shouldFetchLedgerHierarchy() throws Exception {
    final Ledger parent = LedgerGenerator.createRandomLedger();
    final Ledger child = LedgerGenerator.createRandomLedger();
    parent.setSubLedgers(Collections.singletonList(child));
    final Ledger grandChild = LedgerGenerator.createRandomLedger();
    child.setSubLedgers(Collections.singletonList(grandChild));
    final Ledger grandGrandChild = LedgerGenerator.createRandomLedger();
    grandChild.setSubLedgers(Collections.singletonList(grandGrandChild));

    this.testSubject.createLedger(parent);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, parent.getIdentifier());

    final List<Ledger> ledgers = this.testSubject.fetchLedgers();
    Assert.assertTrue(ledgers.size() > 0);

    final Optional<Ledger> optionalLedger = ledgers
        .stream()
        .filter(ledger -> ledger.getIdentifier().equals(parent.getIdentifier()))
        .findAny();

    Assert.assertTrue(optionalLedger.isPresent());
    final Ledger savedParent = optionalLedger.get();
    Assert.assertEquals(parent.getIdentifier(), savedParent.getIdentifier());
    Assert.assertEquals(1, savedParent.getSubLedgers().size());

    final Ledger savedChild = savedParent.getSubLedgers().get(0);
    Assert.assertEquals(child.getIdentifier(), savedChild.getIdentifier());
    Assert.assertEquals(1, savedChild.getSubLedgers().size());

    final Ledger savedGrandChild = savedChild.getSubLedgers().get(0);
    Assert.assertEquals(grandChild.getIdentifier(), savedGrandChild.getIdentifier());
    Assert.assertEquals(1, savedGrandChild.getSubLedgers().size());

    final Ledger savedGrandGrandChild = savedGrandChild.getSubLedgers().get(0);
    Assert.assertEquals(grandGrandChild.getIdentifier(), savedGrandGrandChild.getIdentifier());
    Assert.assertEquals(0, savedGrandGrandChild.getSubLedgers().size());
  }

  @Test
  public void shouldFindLedger() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier());

    final Ledger foundLedger = this.testSubject.findLedger(ledger.getIdentifier());

    Assert.assertNotNull(foundLedger);
    Assert.assertEquals(ledger.getIdentifier(), foundLedger.getIdentifier());
    Assert.assertEquals(ledger.getType(), foundLedger.getType());
    Assert.assertEquals(ledger.getName(), foundLedger.getName());
    Assert.assertEquals(ledger.getDescription(), foundLedger.getDescription());
    Assert.assertTrue(foundLedger.getSubLedgers().size() == 0);
    Assert.assertNotNull(foundLedger.getCreatedBy());
    Assert.assertNotNull(foundLedger.getCreatedOn());
    Assert.assertNull(foundLedger.getLastModifiedBy());
    Assert.assertNull(foundLedger.getLastModifiedOn());
  }

  @Test
  public void shouldNotFindLedgerUnknown() throws Exception {
    try {
      this.testSubject.findLedger(RandomStringUtils.randomAlphanumeric(8));
      Assert.fail();
    } catch (final LedgerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldAddSubLedger() throws Exception {
    final Ledger parentLedger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(parentLedger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, parentLedger.getIdentifier());

    final Ledger subLedger = LedgerGenerator.createRandomLedger();

    this.testSubject.addSubLedger(parentLedger.getIdentifier(), subLedger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, subLedger.getIdentifier());

    final Ledger foundParentLedger = this.testSubject.findLedger(parentLedger.getIdentifier());
    Assert.assertTrue(foundParentLedger.getSubLedgers().size() == 1);
    final Ledger foundSubLedger = foundParentLedger.getSubLedgers().get(0);
    Assert.assertEquals(subLedger.getIdentifier(), foundSubLedger.getIdentifier());
  }

  @Test
  public void shouldNotAddSubLedgerParentUnknown() throws Exception {
    final Ledger subLedger = LedgerGenerator.createRandomLedger();

    try {
      this.testSubject.addSubLedger(RandomStringUtils.randomAlphanumeric(8), subLedger);
      Assert.fail();
    } catch (final LedgerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotAddSubLedgerAlreadyExists() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier());

    try {
      final Ledger subLedger = LedgerGenerator.createRandomLedger();
      subLedger.setIdentifier(ledger.getIdentifier());
      this.testSubject.addSubLedger(ledger.getIdentifier(), subLedger);
      Assert.fail();
    } catch (final LedgerAlreadyExistsException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldModifyLedger() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier());

    ledger.setName(RandomStringUtils.randomAlphabetic(256));
    ledger.setDescription(RandomStringUtils.randomAlphabetic(2048));

    this.testSubject.modifyLedger(ledger.getIdentifier(), ledger);

    this.eventRecorder.wait(EventConstants.PUT_LEDGER, ledger.getIdentifier());

    final Ledger modifiedLedger = this.testSubject.findLedger(ledger.getIdentifier());
    Assert.assertEquals(ledger.getName(), modifiedLedger.getName());
    Assert.assertEquals(ledger.getDescription(), modifiedLedger.getDescription());
    Assert.assertNotNull(modifiedLedger.getLastModifiedBy());
    Assert.assertNotNull(modifiedLedger.getLastModifiedOn());
  }

  @Test
  public void shouldNotModifyLedgerIdentifierMismatch() throws Exception {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger.getIdentifier());

    try {
      this.testSubject.modifyLedger(RandomStringUtils.randomAlphanumeric(8), ledger);
      Assert.fail();
    } catch (final LedgerValidationException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotModifyLedgerUnknown() {
    final Ledger ledger = LedgerGenerator.createRandomLedger();

    try {
      this.testSubject.modifyLedger(ledger.getIdentifier(), ledger);
      Assert.fail();
    } catch (final LedgerNotFoundException ex) {
      // do nothing , expected
    }
  }

  @Test
  public void shouldDeleteLedger() throws Exception {
    final Ledger ledger2delete = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger2delete);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger2delete.getIdentifier());

    this.testSubject.deleteLedger(ledger2delete.getIdentifier());

    this.eventRecorder.wait(EventConstants.DELETE_LEDGER, ledger2delete.getIdentifier());

    try {
      this.testSubject.findLedger(ledger2delete.getIdentifier());
      Assert.fail();
    } catch (final LedgerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotDeleteLedgerUnknown() throws Exception {
    try {
      this.testSubject.deleteLedger(RandomStringUtils.randomAlphanumeric(8));
      Assert.fail();
    } catch (final LedgerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotDeleteLedgerHoldsSubLedger() throws Exception {
    final Ledger ledger2delete = LedgerGenerator.createRandomLedger();
    ledger2delete.setSubLedgers(Collections.singletonList(LedgerGenerator.createRandomLedger()));

    this.testSubject.createLedger(ledger2delete);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger2delete.getIdentifier());

    try {
      this.testSubject.deleteLedger(ledger2delete.getIdentifier());
      Assert.fail();
    } catch (final LedgerReferenceExistsException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotDeleteLedgerHoldsAccount() throws Exception {
    final Ledger ledger2delete = LedgerGenerator.createRandomLedger();

    this.testSubject.createLedger(ledger2delete);

    this.eventRecorder.wait(EventConstants.POST_LEDGER, ledger2delete.getIdentifier());

    final Account ledgerAccount = AccountGenerator.createRandomAccount(ledger2delete.getIdentifier());

    this.testSubject.createAccount(ledgerAccount);

    this.eventRecorder.wait(EventConstants.POST_ACCOUNT, ledgerAccount.getIdentifier());

    try {
      this.testSubject.deleteLedger(ledger2delete.getIdentifier());
      Assert.fail();
    } catch (final LedgerReferenceExistsException ex) {
      // do nothing, expected
    }
  }

  @Configuration
  @EnableEventRecording
  @EnableFeignClients(basePackages = {"io.mifos.accounting.api.v1"})
  @RibbonClient(name = APP_NAME)
  @Import({AccountingServiceConfiguration.class})
  @ComponentScan("io.mifos.accounting.listener")
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean
    public Logger logger() {
      return LoggerFactory.getLogger("test-logger");
    }
  }
}
