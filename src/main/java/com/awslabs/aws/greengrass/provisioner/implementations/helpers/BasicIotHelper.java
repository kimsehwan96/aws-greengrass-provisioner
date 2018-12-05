package com.awslabs.aws.greengrass.provisioner.implementations.helpers;

import com.awslabs.aws.greengrass.provisioner.data.KeysAndCertificate;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.*;

import javax.inject.Inject;
import java.util.List;

@Slf4j
public class BasicIotHelper implements IotHelper {
    public static final String CREDENTIALS = "credentials/";
    @Inject
    IotClient iotClient;
    @Getter(lazy = true)
    private final String endpoint = describeEndpoint();
    @Inject
    IoHelper ioHelper;
    @Inject
    GGConstants ggConstants;
    @Inject
    JsonHelper jsonHelper;

    @Inject
    public BasicIotHelper() {
    }

    private String describeEndpoint() {
        return iotClient.describeEndpoint().endpointAddress();
    }

    @Override
    public String createThing(String name) {
        CreateThingRequest createThingRequest = CreateThingRequest.builder()
                .thingName(name)
                .build();

        try {
            return iotClient.createThing(createThingRequest).thingArn();
        } catch (ResourceAlreadyExistsException e) {
            if (e.getMessage().contains("with different tags")) {
                log.info("The thing [" + name + "] already exists with different tags/attributes (e.g. immutable or other attributes)");

                DescribeThingRequest describeThingRequest = DescribeThingRequest.builder()
                        .thingName(name)
                        .build();
                return iotClient.describeThing(describeThingRequest).thingArn();
            }

            throw new UnsupportedOperationException(e);
        }
    }

    private String credentialDirectoryForGroupId(String groupId) {
        return CREDENTIALS + groupId;
    }

    private String createKeysandCertificateFilenameForGroupId(String groupId, String subName) {
        return credentialDirectoryForGroupId(groupId) + "/" + subName + ".createKeysAndCertificate.serialized";
    }

    private boolean certificateExists(String certificateId) {
        DescribeCertificateRequest describeCertificateRequest = DescribeCertificateRequest.builder()
                .certificateId(certificateId)
                .build();

        try {
            iotClient.describeCertificate(describeCertificateRequest);
        } catch (ResourceNotFoundException e) {
            return false;
        }

        return true;
    }

    @Override
    public KeysAndCertificate createOrLoadKeysAndCertificate(String groupId, String subName) {
        String credentialsDirectory = credentialDirectoryForGroupId(groupId);

        ioHelper.createDirectoryIfNecessary(credentialsDirectory);

        String createKeysAndCertificateFilename = createKeysandCertificateFilenameForGroupId(groupId, subName);

        if (ioHelper.exists(createKeysAndCertificateFilename)) {
            log.info("- Attempting to reuse existing keys.");

            KeysAndCertificate keysAndCertificate = ioHelper.deserializeKeys(ioHelper.readFile(createKeysAndCertificateFilename), jsonHelper);

            if (certificateExists(keysAndCertificate.getCertificateId())) {
                log.info("- Reusing existing keys.");
                return keysAndCertificate;
            } else {
                log.warn("- Existing certificate is not in AWS IoT.  It may have been deleted.");
            }
        }

        // Let them know that they'll need to re-run the bootstrap script because the core's keys changed
        boolean isCore = subName.equals(DeploymentHelper.CORE_SUB_NAME);
        String supplementalMessage = isCore ? "  If you have an existing deployment for this group you'll need to re-run the bootstrap script since the core certificate ARN will change." : "";
        log.info("- Keys not found, creating new keys." + supplementalMessage);
        CreateKeysAndCertificateRequest createKeysAndCertificateRequest = CreateKeysAndCertificateRequest.builder()
                .setAsActive(true)
                .build();

        CreateKeysAndCertificateResponse createKeysAndCertificateResponse = iotClient.createKeysAndCertificate(createKeysAndCertificateRequest);

        ioHelper.writeFile(createKeysAndCertificateFilename, ioHelper.serializeKeys(createKeysAndCertificateResponse, jsonHelper).getBytes());

        String deviceName = isCore ? groupId : ggConstants.trimGgdPrefix(subName);
        String privateKeyFilename = "build/" + String.join(".", deviceName, "pem", "key");
        String publicSignedCertificateFilename = "build/" + String.join(".", deviceName, "pem", "crt");

        ioHelper.writeFile(privateKeyFilename, createKeysAndCertificateResponse.keyPair().privateKey().getBytes());
        log.info("Device private key written to [" + privateKeyFilename + "]");
        ioHelper.writeFile(publicSignedCertificateFilename, createKeysAndCertificateResponse.certificatePem().getBytes());
        log.info("Device public signed certificate key written to [" + publicSignedCertificateFilename + "]");

        return KeysAndCertificate.from(createKeysAndCertificateResponse);
    }

    private boolean policyExists(String name) {
        GetPolicyRequest getPolicyRequest = GetPolicyRequest.builder()
                .policyName(name)
                .build();

        try {
            iotClient.getPolicy(getPolicyRequest);
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    @Override
    public void createPolicyIfNecessary(String name, String document) {
        if (policyExists(name)) {
            return;
        }

        CreatePolicyRequest createPolicyRequest = CreatePolicyRequest.builder()
                .policyName(name)
                .policyDocument(document)
                .build();

        iotClient.createPolicy(createPolicyRequest);
    }

    @Override
    public void attachPrincipalPolicy(String policyName, String certificateArn) {
        AttachPolicyRequest attachPolicyRequest = AttachPolicyRequest.builder()
                .policyName(policyName)
                .target(certificateArn)
                .build();

        iotClient.attachPolicy(attachPolicyRequest);
    }

    @Override
    public void attachThingPrincipal(String thingName, String certificateArn) {
        AttachThingPrincipalRequest attachThingPrincipalRequest = AttachThingPrincipalRequest.builder()
                .thingName(thingName)
                .principal(certificateArn)
                .build();

        iotClient.attachThingPrincipal(attachThingPrincipalRequest);
    }

    @Override
    public String getThingPrincipal(String thingName) {
        ListThingPrincipalsRequest listThingPrincipalsRequest = ListThingPrincipalsRequest.builder()
                .thingName(thingName)
                .build();

        ListThingPrincipalsResponse listThingPrincipalsResponse = iotClient.listThingPrincipals(listThingPrincipalsRequest);

        List<String> principals = listThingPrincipalsResponse.principals();

        if ((principals == null) || (principals.size() == 0)) {
            return null;
        }

        return principals.get(0);
    }

    @Override
    public String getThingArn(String thingName) {
        DescribeThingRequest describeThingRequest = DescribeThingRequest.builder()
                .thingName(thingName)
                .build();

        DescribeThingResponse describeThingResponse = iotClient.describeThing(describeThingRequest);

        if (describeThingResponse == null) {
            return null;
        }

        return describeThingResponse.thingArn();
    }

    @Override
    public String getCredentialProviderUrl() {
        DescribeEndpointRequest describeEndpointRequest = DescribeEndpointRequest.builder()
                .endpointType("iot:CredentialProvider")
                .build();

        return iotClient.describeEndpoint(describeEndpointRequest).endpointAddress();
    }

    @Override
    public CreateRoleAliasResponse createRoleAliasIfNecessary(Role serviceRole, String roleAlias) {
        CreateRoleAliasRequest createRoleAliasRequest = CreateRoleAliasRequest.builder()
                .roleArn(serviceRole.arn())
                .roleAlias(roleAlias)
                .build();

        try {
            return iotClient.createRoleAlias(createRoleAliasRequest);
        } catch (ResourceAlreadyExistsException e) {
            // Already exists, delete so we can try
            DeleteRoleAliasRequest deleteRoleAliasRequest = DeleteRoleAliasRequest.builder()
                    .roleAlias(roleAlias)
                    .build();
            iotClient.deleteRoleAlias(deleteRoleAliasRequest);
        }

        return iotClient.createRoleAlias(createRoleAliasRequest);
    }
}
