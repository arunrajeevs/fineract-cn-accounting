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
package io.mifos.accounting.service.internal.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

  List<AccountEntity> findByLedger(final LedgerEntity ledgerEntity);

  Page<AccountEntity> findByLedger(final LedgerEntity ledgerEntity, final Pageable pageable);

  AccountEntity findByIdentifier(final String identifier);

  Page<AccountEntity> findByIdentifierContaining(final String identifier, final Pageable pageable);

  Page<AccountEntity> findByIdentifierContainingAndStateNot(final String identifier, final String state, final Pageable pageable);

  Page<AccountEntity> findByStateNot(final String state, final Pageable pageable);
}
