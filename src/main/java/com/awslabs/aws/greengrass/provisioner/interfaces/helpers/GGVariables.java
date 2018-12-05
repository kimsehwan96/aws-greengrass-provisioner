package com.awslabs.aws.greengrass.provisioner.interfaces.helpers;

import software.amazon.awssdk.regions.Region;

public interface GGVariables {
    String getCoreThingName(String groupName);

    String getCoreDefinitionName(String groupName);

    String getCorePolicyName(String groupName);

    String getDeviceShadowTopicFilterName(String deviceThingName);

    String getGgHost(Region region);

    String getDeviceDefinitionName(String groupName);
}

