package com.awslabs.aws.greengrass.provisioner.data.conf;

import org.immutables.value.Value;
import software.amazon.awssdk.services.greengrass.model.Connector;
import software.amazon.awssdk.services.greengrass.model.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Value.Immutable
public abstract class DeploymentConf {
    public abstract String getName();

    public abstract String getGroupName();

    public abstract List<String> getFunctions();

    public abstract RoleConf getCoreRoleConf();

    public abstract Optional<RoleConf> getLambdaRoleConf();

    public abstract Optional<RoleConf> getServiceRoleConf();

    public abstract List<String> getGgds();

    public abstract Map<String, String> getEnvironmentVariables();

    public abstract boolean isSyncShadow();

    public abstract List<String> getConnectors();

    public abstract Optional<List<Logger>> getLoggers();
}
