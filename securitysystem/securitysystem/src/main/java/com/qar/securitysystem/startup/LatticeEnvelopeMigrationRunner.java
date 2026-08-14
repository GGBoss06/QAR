package com.qar.securitysystem.startup;

import com.qar.securitysystem.abe.AccessPurpose;
import com.qar.securitysystem.abe.FileKeyEnvelopeService;
import com.qar.securitysystem.abe.lattice.LatticeAbeService;
import com.qar.securitysystem.abe.lattice.LatticeUserSecretKeyService;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.repo.FileRecordRepository;
import com.qar.securitysystem.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class LatticeEnvelopeMigrationRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LatticeEnvelopeMigrationRunner.class);

    private final FileRecordRepository fileRecordRepository;
    private final FileKeyEnvelopeService fileKeyEnvelopeService;
    private final LatticeAbeService latticeAbeService;
    private final UserRepository userRepository;
    private final LatticeUserSecretKeyService userSecretKeyService;
    private final boolean enabled;

    public LatticeEnvelopeMigrationRunner(FileRecordRepository fileRecordRepository,
                                          FileKeyEnvelopeService fileKeyEnvelopeService,
                                          LatticeAbeService latticeAbeService,
                                          UserRepository userRepository,
                                          LatticeUserSecretKeyService userSecretKeyService,
                                          @Value("${app.crypto.migrate-legacy-envelopes:true}") boolean enabled) {
        this.fileRecordRepository = fileRecordRepository;
        this.fileKeyEnvelopeService = fileKeyEnvelopeService;
        this.latticeAbeService = latticeAbeService;
        this.userRepository = userRepository;
        this.userSecretKeyService = userSecretKeyService;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long sanitizedArchives = userSecretKeyService.sanitizeArchivedBundles();
        if (sanitizedArchives > 0) {
            LOG.info("Removed private key material from {} archived lattice bundles", sanitizedArchives);
        }
        if (!enabled) {
            return;
        }
        List<FileRecordEntity> changed = new ArrayList<>();
        for (FileRecordEntity record : fileRecordRepository.findAll()) {
            String wrappedKey = record.getWrappedKey();
            if (!latticeAbeService.isLegacyEnvelope(wrappedKey)) {
                continue;
            }
            byte[] fileKey = fileKeyEnvelopeService.unwrapForSystem(record, AccessPurpose.SYSTEM_INTERNAL);
            try {
                record.setWrappedKey(fileKeyEnvelopeService.wrapForStorage(fileKey, record.getPolicy()));
                changed.add(record);
            } finally {
                Arrays.fill(fileKey, (byte) 0);
            }
        }
        if (!changed.isEmpty()) {
            fileRecordRepository.saveAll(changed);
            LOG.info("Migrated {} legacy lattice key envelopes to user-bound v3", changed.size());
        }
        int refreshedBundles = 0;
        for (var user : userRepository.findAll()) {
            if (!LatticeUserSecretKeyService.isUserAccessEnabled(user)) {
                continue;
            }
            var before = userSecretKeyService.loadIfExists(user.getId());
            if (before == null
                    || !com.qar.securitysystem.abe.lattice.LatticeAttributeKeyService.KEY_SCHEME.equals(before.keyScheme)
                    || before.recipientPrivateKey == null || before.recipientPrivateKey.isBlank()) {
                userSecretKeyService.getOrCreate(user);
                refreshedBundles++;
            }
        }
        if (refreshedBundles > 0) {
            LOG.info("Refreshed {} user lattice bundles with recipient-bound Kyber keys", refreshedBundles);
        }
    }
}
