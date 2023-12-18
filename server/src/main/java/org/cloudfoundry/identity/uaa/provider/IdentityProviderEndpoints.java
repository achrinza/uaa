/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */
package org.cloudfoundry.identity.uaa.provider;

import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.ZoneDoesNotExistsException;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cloudfoundry.identity.uaa.authentication.manager.DynamicLdapAuthenticationManager;
import org.cloudfoundry.identity.uaa.authentication.manager.LdapLoginAuthenticationManager;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.audit.event.EntityDeletedEvent;
import org.cloudfoundry.identity.uaa.provider.saml.SamlIdentityProviderConfigurator;
import org.cloudfoundry.identity.uaa.scim.ScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.scim.ScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.ObjectUtils;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.List;

import static org.cloudfoundry.identity.uaa.constants.OriginKeys.LDAP;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.OAUTH20;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.OIDC10;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.UAA;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.EXPECTATION_FAILED;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import static org.springframework.util.StringUtils.hasText;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.PATCH;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@RequestMapping("/identity-providers")
@RestController
public class IdentityProviderEndpoints implements ApplicationEventPublisherAware {

    protected static Logger logger = LoggerFactory.getLogger(IdentityProviderEndpoints.class);

    private final IdentityProviderProvisioning identityProviderProvisioning;
    private final ScimGroupExternalMembershipManager scimGroupExternalMembershipManager;
    private final ScimGroupProvisioning scimGroupProvisioning;
    private final NoOpLdapLoginAuthenticationManager noOpManager = new NoOpLdapLoginAuthenticationManager();
    private final SamlIdentityProviderConfigurator samlConfigurator;
    private final IdentityProviderConfigValidator configValidator;
    private final IdentityZoneManager identityZoneManager;
    private final IdentityZoneProvisioning identityZoneProvisioning;
    private final TransactionTemplate transactionTemplate;

    private ApplicationEventPublisher publisher = null;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.publisher = applicationEventPublisher;
    }

    public IdentityProviderEndpoints(
            final @Qualifier("identityProviderProvisioning") IdentityProviderProvisioning identityProviderProvisioning,
            final @Qualifier("externalGroupMembershipManager") ScimGroupExternalMembershipManager scimGroupExternalMembershipManager,
            final @Qualifier("scimGroupProvisioning") ScimGroupProvisioning scimGroupProvisioning,
            final @Qualifier("metaDataProviders") SamlIdentityProviderConfigurator samlConfigurator,
            final @Qualifier("identityProviderConfigValidator") IdentityProviderConfigValidator configValidator,
            final IdentityZoneManager identityZoneManager,
            final @Qualifier("identityZoneProvisioning") IdentityZoneProvisioning identityZoneProvisioning,
            final @Qualifier("transactionManager") PlatformTransactionManager transactionManager
    ) {
        this.identityProviderProvisioning = identityProviderProvisioning;
        this.scimGroupExternalMembershipManager = scimGroupExternalMembershipManager;
        this.scimGroupProvisioning = scimGroupProvisioning;
        this.samlConfigurator = samlConfigurator;
        this.configValidator = configValidator;
        this.identityZoneManager = identityZoneManager;
        this.identityZoneProvisioning = identityZoneProvisioning;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @RequestMapping(method = POST)
    public ResponseEntity<IdentityProvider> createIdentityProvider(@RequestBody IdentityProvider body, @RequestParam(required = false, defaultValue = "false") boolean rawConfig) throws MetadataProviderException{
        body.setSerializeConfigRaw(rawConfig);
        String zoneId = identityZoneManager.getCurrentIdentityZoneId();
        body.setIdentityZoneId(zoneId);
        try {
            configValidator.validate(body);
        } catch (IllegalArgumentException e) {
            logger.debug("IdentityProvider[origin="+body.getOriginKey()+"; zone="+body.getIdentityZoneId()+"] - Configuration validation error.", e);
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }
        if (OriginKeys.SAML.equals(body.getType())) {
            SamlIdentityProviderDefinition definition = ObjectUtils.castInstance(body.getConfig(), SamlIdentityProviderDefinition.class);
            definition.setZoneId(zoneId);
            definition.setIdpEntityAlias(body.getOriginKey());
            samlConfigurator.validateSamlIdentityProviderDefinition(definition);
            body.setConfig(definition);
        }

        // at this point, the alias ID must not be set
        if (hasText(body.getAliasId())) {
            logger.debug("IdentityProvider[origin=" + body.getOriginKey() + "; zone=" + body.getIdentityZoneId() + "] - Alias ID was not null.");
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }

        if (hasText(body.getAliasZid())) {
            // check if the zone exists
            try {
                identityZoneProvisioning.retrieve(body.getAliasZid());
            } catch (final ZoneDoesNotExistsException e) {
                logger.debug("IdentityProvider[origin=" + body.getOriginKey() + "; zone=" + body.getIdentityZoneId() + "] - Zone referenced in alias zone ID does not exist.");
                return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
            }

            // mirroring is only allowed from or to the "uaa" zone
            if (!zoneId.equals(UAA) && !body.getAliasZid().equals(UAA)) {
                logger.debug("IdentityProvider[origin=" + body.getOriginKey() + "; zone=" + body.getIdentityZoneId() + "] - Invalid: Alias ZID set to custom zone, IdP created in custom zone.");
                return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
            }

            // mirroring cannot be done to the same zone
            if (body.getAliasZid().equals(zoneId)) {
                logger.debug("IdentityProvider[origin=" + body.getOriginKey() + "; zone=" + body.getIdentityZoneId() + "] - Invalid: Alias ZID equal to current IdZ.");
                return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
            }
        }

        // persist IdP and mirror if necessary
        final IdentityProvider createdIdp;
        try {
            createdIdp = transactionTemplate.execute(txStatus -> {
                final IdentityProvider createdOriginalIdp = identityProviderProvisioning.create(body, zoneId);
                createdOriginalIdp.setSerializeConfigRaw(rawConfig);
                redactSensitiveData(createdOriginalIdp);

                return ensureConsistencyOfMirroredIdp(createdOriginalIdp);
            });
        } catch (final TransactionException e) {
            if (e.getCause() instanceof IdpAlreadyExistsException) {
                return new ResponseEntity<>(body, CONFLICT);
            }

            logger.error("Unable to create IdentityProvider[origin=" + body.getOriginKey() + "; zone=" + body.getIdentityZoneId() + "]", e);
            return new ResponseEntity<>(body, INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(createdIdp, CREATED);
    }

    /**
     * Ensure consistency with a mirrored IdP referenced in the original IdPs alias properties. If the IdP has both its
     * alias ID and alias ZID set, the existing mirrored IdP is updated. If only the alias ZID is set, a new mirrored
     * IdP is created.
     * This method should be executed in a transaction together with the original create or update operation.
     *
     * @param originalIdp the original IdP; must be persisted, i.e., have an ID, already
     * @return the original IdP after the operation, with a potentially updated "aliasId" field
     * @throws IdpMirroringFailedException if a new mirrored IdP needs to be created, but the zone referenced in
     *                                     'aliasZid' does not exist
     * @throws IdpMirroringFailedException if 'aliasId' and 'aliasZid' are set in the original IdP, but the referenced
     *                                     mirrored IdP could not be found
     */
    private IdentityProvider ensureConsistencyOfMirroredIdp(final IdentityProvider originalIdp) throws IdpMirroringFailedException {
        if (!hasText(originalIdp.getAliasZid())) {
            // no mirroring is necessary
            return originalIdp;
        }

        final IdentityProvider mirroredIdp = new IdentityProvider<>()
                .setActive(originalIdp.isActive())
                .setConfig(originalIdp.getConfig())
                .setName(originalIdp.getName())
                .setOriginKey(originalIdp.getOriginKey())
                .setType(originalIdp.getType())
                // reference the ID and zone ID of the initial IdP entry
                .setAliasZid(originalIdp.getIdentityZoneId())
                .setAliasId(originalIdp.getId())
                .setIdentityZoneId(originalIdp.getAliasZid());
        mirroredIdp.setSerializeConfigRaw(originalIdp.isSerializeConfigRaw());

        if (hasText(originalIdp.getAliasId())) {
            // retrieve and update existing mirrored IdP
            final IdentityProvider existingMirroredIdp;
            try {
                existingMirroredIdp = identityProviderProvisioning.retrieve(
                        originalIdp.getAliasId(),
                        originalIdp.getAliasZid()
                );
            } catch (final EmptyResultDataAccessException e) {
                throw new IdpMirroringFailedException(String.format(
                        "The IdP referenced in the 'aliasId' and 'aliasZid' properties of IdP '%s' does not exist.",
                        originalIdp.getId()
                ), e);
            }
            mirroredIdp.setId(existingMirroredIdp.getId());
            identityProviderProvisioning.update(mirroredIdp, originalIdp.getAliasZid());
            return originalIdp;
        }

        // check if IdZ referenced in 'aliasZid' exists
        try {
            identityZoneProvisioning.retrieve(originalIdp.getAliasZid());
        } catch (final ZoneDoesNotExistsException e) {
            throw new IdpMirroringFailedException(String.format(
                    "Could not mirror IdP '%s' to zone '%s', as zone does not exist.",
                    originalIdp.getId(),
                    originalIdp.getAliasZid()
            ), e);
        }

        // create new mirrored IdP in alias zid
        final IdentityProvider persistedMirroredIdp = identityProviderProvisioning.create(
                mirroredIdp,
                originalIdp.getAliasZid()
        );

        // update alias ID in original IdP
        originalIdp.setAliasId(persistedMirroredIdp.getId());
        return identityProviderProvisioning.update(originalIdp, originalIdp.getIdentityZoneId());
    }

    @RequestMapping(value = "{id}", method = DELETE)
    @Transactional
    public ResponseEntity<IdentityProvider> deleteIdentityProvider(@PathVariable String id, @RequestParam(required = false, defaultValue = "false") boolean rawConfig) {
        String identityZoneId = identityZoneManager.getCurrentIdentityZoneId();
        IdentityProvider existing = identityProviderProvisioning.retrieve(id, identityZoneId);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // delete mirrored IdP if alias fields are set
        if (existing != null && hasText(existing.getAliasZid()) && hasText(existing.getAliasId())) {
            IdentityProvider mirroredIdp = identityProviderProvisioning.retrieve(existing.getAliasId(), existing.getAliasZid());
            mirroredIdp.setSerializeConfigRaw(rawConfig);
            publisher.publishEvent(new EntityDeletedEvent<>(mirroredIdp, authentication, identityZoneId));
        }

        if (publisher!=null && existing!=null) {
            existing.setSerializeConfigRaw(rawConfig);
            publisher.publishEvent(new EntityDeletedEvent<>(existing, authentication, identityZoneId));
            redactSensitiveData(existing);
            return new ResponseEntity<>(existing, OK);
        } else {
            return new ResponseEntity<>(UNPROCESSABLE_ENTITY);
        }
    }

    @RequestMapping(value = "{id}", method = PUT)
    public ResponseEntity<IdentityProvider> updateIdentityProvider(@PathVariable String id, @RequestBody IdentityProvider body, @RequestParam(required = false, defaultValue = "false") boolean rawConfig) throws MetadataProviderException {
        body.setSerializeConfigRaw(rawConfig);
        String zoneId = identityZoneManager.getCurrentIdentityZoneId();
        IdentityProvider existing = identityProviderProvisioning.retrieve(id, zoneId);
        body.setId(id);
        body.setIdentityZoneId(zoneId);
        patchSensitiveData(id, body);
        try {
            configValidator.validate(body);
        } catch (IllegalArgumentException e) {
            logger.debug("IdentityProvider[origin="+body.getOriginKey()+"; zone="+body.getIdentityZoneId()+"] - Configuration validation error for update.", e);
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }

        if (!isValidAliasPropertyUpdate(body, existing)) {
            logger.error("IdentityProvider[origin="+body.getOriginKey()+"; zone="+body.getIdentityZoneId()+"] - Alias ID and/or ZID changed during update of already mirrored IdP.");
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }

        if (OriginKeys.SAML.equals(body.getType())) {
            body.setOriginKey(existing.getOriginKey()); //we do not allow origin to change for a SAML provider, since that can cause clashes
            SamlIdentityProviderDefinition definition = ObjectUtils.castInstance(body.getConfig(), SamlIdentityProviderDefinition.class);
            definition.setZoneId(zoneId);
            definition.setIdpEntityAlias(body.getOriginKey());
            samlConfigurator.validateSamlIdentityProviderDefinition(definition);
            body.setConfig(definition);
        }

        final IdentityProvider updatedIdp = transactionTemplate.execute(txStatus -> {
            final IdentityProvider updatedOriginalIdp = identityProviderProvisioning.update(body, zoneId);
            return ensureConsistencyOfMirroredIdp(updatedOriginalIdp);
        });

        updatedIdp.setSerializeConfigRaw(rawConfig);
        redactSensitiveData(updatedIdp);
        return new ResponseEntity<>(updatedIdp, OK);
    }

    /**
     * Checks whether an update operation is valid in regard to the alias properties.
     *
     * @param updatePayload the updated version of the IdP to be persisted
     * @param existingIdp the existing version of the IdP
     * @return whether the update of the alias properties is valid
     */
    private static boolean isValidAliasPropertyUpdate(
            final IdentityProvider updatePayload,
            final IdentityProvider existingIdp
    ) {
        if (!hasText(existingIdp.getAliasId()) && !hasText(existingIdp.getAliasZid())) {
            // no alias properties set previously

            if (hasText(updatePayload.getAliasId())) {
                return false; // 'aliasId' must be empty
            }

            if (!hasText(updatePayload.getAliasZid())) {
                return true; // no mirroring necessary
            }

            // one of the zones must be "uaa"
            return updatePayload.getAliasZid().equals(UAA) || updatePayload.getIdentityZoneId().equals(UAA);
        }

        if (!hasText(existingIdp.getAliasId()) || !hasText(existingIdp.getAliasZid())) {
            // at this point, we expect both properties to be set -> if not, the IdP is in an inconsistent state
            throw new IllegalStateException(String.format(
                    "Both alias ID and alias ZID expected to be set for IdP '%s' in zone '%s'.",
                    existingIdp.getId(),
                    existingIdp.getIdentityZoneId()
            ));
        }

        // both properties must be equal in the update payload
        return existingIdp.getAliasId().equals(updatePayload.getAliasId())
                && existingIdp.getAliasZid().equals(updatePayload.getAliasZid());
    }

    @RequestMapping (value = "{id}/status", method = PATCH)
    public ResponseEntity<IdentityProviderStatus> updateIdentityProviderStatus(@PathVariable String id, @RequestBody IdentityProviderStatus body) {
        String zoneId = identityZoneManager.getCurrentIdentityZoneId();
        IdentityProvider existing = identityProviderProvisioning.retrieve(id, zoneId);
        if(body.getRequirePasswordChange() == null || !body.getRequirePasswordChange()) {
            logger.debug("Invalid payload. The property requirePasswordChangeRequired needs to be set");
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }
        if(!UAA.equals(existing.getType())) {
            logger.debug("Invalid operation. This operation is not supported on external IDP");
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }
        UaaIdentityProviderDefinition uaaIdentityProviderDefinition = ObjectUtils.castInstance(existing.getConfig(), UaaIdentityProviderDefinition.class);
        if(uaaIdentityProviderDefinition == null || uaaIdentityProviderDefinition.getPasswordPolicy() == null) {
            logger.debug("IDP does not have an existing PasswordPolicy. Operation not supported");
            return new ResponseEntity<>(body, UNPROCESSABLE_ENTITY);
        }
        uaaIdentityProviderDefinition.getPasswordPolicy().setPasswordNewerThan(new Date(System.currentTimeMillis()));
        identityProviderProvisioning.update(existing, zoneId);
        logger.info("PasswordChangeRequired property set for Identity Provider: " + existing.getId());
        return  new ResponseEntity<>(body, OK);
    }

    @RequestMapping(method = GET)
    public ResponseEntity<List<IdentityProvider>> retrieveIdentityProviders(@RequestParam(value = "active_only", required = false) String activeOnly, @RequestParam(required = false, defaultValue = "false") boolean rawConfig) {
        boolean retrieveActiveOnly = Boolean.parseBoolean(activeOnly);
        List<IdentityProvider> identityProviderList = identityProviderProvisioning.retrieveAll(retrieveActiveOnly, identityZoneManager.getCurrentIdentityZoneId());
        for(IdentityProvider idp : identityProviderList) {
            idp.setSerializeConfigRaw(rawConfig);
            redactSensitiveData(idp);
        }
        return new ResponseEntity<>(identityProviderList, OK);
    }

    @RequestMapping(value = "{id}", method = GET)
    public ResponseEntity<IdentityProvider> retrieveIdentityProvider(@PathVariable String id, @RequestParam(required = false, defaultValue = "false") boolean rawConfig) {
        IdentityProvider identityProvider = identityProviderProvisioning.retrieve(id, identityZoneManager.getCurrentIdentityZoneId());
        identityProvider.setSerializeConfigRaw(rawConfig);
        redactSensitiveData(identityProvider);
        return new ResponseEntity<>(identityProvider, OK);
    }

    @RequestMapping(value = "test", method = POST)
    public ResponseEntity<String> testIdentityProvider(@RequestBody IdentityProviderValidationRequest body) {
        String exception = "ok";
        HttpStatus status = OK;
        //create the LDAP IDP
        DynamicLdapAuthenticationManager manager = new DynamicLdapAuthenticationManager(
            ObjectUtils.castInstance(body.getProvider().getConfig(),LdapIdentityProviderDefinition.class),
            scimGroupExternalMembershipManager,
            scimGroupProvisioning,
            noOpManager
        );
        try {
            //attempt authentication
            Authentication result = manager.authenticate(body.getCredentials());
            if ((result == null) || (result != null && !result.isAuthenticated())) {
                status = EXPECTATION_FAILED;
            }
        } catch (BadCredentialsException x) {
            status = EXPECTATION_FAILED;
            exception = "bad credentials";
        } catch (InternalAuthenticationServiceException x) {
            status = BAD_REQUEST;
            exception = getExceptionString(x);
        } catch (Exception x) {
            logger.error("Identity provider validation failed.", x);
            status = INTERNAL_SERVER_ERROR;
            exception = "check server logs";
        }finally {
            //destroy IDP
            manager.destroy();
        }
        //return results
        return new ResponseEntity<>(JsonUtils.writeValueAsString(exception), status);
    }


    @ExceptionHandler(MetadataProviderException.class)
    public ResponseEntity<String> handleMetadataProviderException(MetadataProviderException e) {
        if (e.getMessage().contains("Duplicate")) {
            return new ResponseEntity<>(e.getMessage(), CONFLICT);
        } else {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @ExceptionHandler(JsonUtils.JsonUtilException.class)
    public ResponseEntity<String> handleMetadataProviderException() {
        return new ResponseEntity<>("Invalid provider configuration.", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<String> handleProviderNotFoundException() {
        return new ResponseEntity<>("Provider not found.", HttpStatus.NOT_FOUND);
    }


    protected String getExceptionString(Exception x) {
        StringWriter writer = new StringWriter();
        x.printStackTrace(new PrintWriter(writer));
        return writer.getBuffer().toString();
    }

    protected static class NoOpLdapLoginAuthenticationManager extends LdapLoginAuthenticationManager {
        public NoOpLdapLoginAuthenticationManager() {
            super(null);
        }

        @Override
        public Authentication authenticate(Authentication request) throws AuthenticationException {
            return request;
        }
    }

    protected void patchSensitiveData(String id, IdentityProvider provider) {
        String zoneId = identityZoneManager.getCurrentIdentityZoneId();
        if (provider.getConfig() == null) {
            return;
        }
        switch (provider.getType()) {
            case LDAP: {
                if (provider.getConfig() instanceof LdapIdentityProviderDefinition) {
                    LdapIdentityProviderDefinition definition = (LdapIdentityProviderDefinition) provider.getConfig();
                    if (definition.getBindPassword() == null) {
                        IdentityProvider existing = identityProviderProvisioning.retrieve(id, zoneId);
                        if (existing!=null &&
                            existing.getConfig()!=null &&
                            existing.getConfig() instanceof LdapIdentityProviderDefinition) {
                            LdapIdentityProviderDefinition existingDefinition = (LdapIdentityProviderDefinition)existing.getConfig();
                            definition.setBindPassword(existingDefinition.getBindPassword());
                        }
                    }
                }
                break;
            }
            case OAUTH20 :
            case OIDC10 : {
                if (provider.getConfig() instanceof AbstractExternalOAuthIdentityProviderDefinition) {
                    AbstractExternalOAuthIdentityProviderDefinition definition = (AbstractExternalOAuthIdentityProviderDefinition) provider.getConfig();
                    if (definition.getRelyingPartySecret() == null) {
                        IdentityProvider existing = identityProviderProvisioning.retrieve(id, zoneId);
                        if (existing!=null &&
                            existing.getConfig()!=null &&
                            existing.getConfig() instanceof AbstractExternalOAuthIdentityProviderDefinition) {
                            AbstractExternalOAuthIdentityProviderDefinition existingDefinition = (AbstractExternalOAuthIdentityProviderDefinition)existing.getConfig();
                            definition.setRelyingPartySecret(existingDefinition.getRelyingPartySecret());
                        }
                    }
                }
                break;
            }
            default:
                break;

        }
    }

    protected void redactSensitiveData(IdentityProvider provider) {
        if (provider.getConfig() == null) {
            return;
        }
        switch (provider.getType()) {
            case LDAP: {
                if (provider.getConfig() instanceof LdapIdentityProviderDefinition) {
                    logger.debug("Removing bind password from LDAP provider id:"+provider.getId());
                    LdapIdentityProviderDefinition definition = (LdapIdentityProviderDefinition) provider.getConfig();
                    definition.setBindPassword(null);
                }
                break;
            }
            case OAUTH20 :
            case OIDC10 : {
                if (provider.getConfig() instanceof AbstractExternalOAuthIdentityProviderDefinition) {
                    logger.debug("Removing relying secret from OAuth/OIDC provider id:"+provider.getId());
                    AbstractExternalOAuthIdentityProviderDefinition definition = (AbstractExternalOAuthIdentityProviderDefinition) provider.getConfig();
                    definition.setRelyingPartySecret(null);
                }
                break;
            }
            default:
                break;

        }
    }

}
