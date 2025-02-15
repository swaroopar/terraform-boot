/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: Huawei Inc.
 */

package org.eclipse.xpanse.terra.boot.terraform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.xpanse.terra.boot.models.exceptions.TerraformExecutorException;
import org.eclipse.xpanse.terra.boot.terraform.utils.SystemCmd;
import org.eclipse.xpanse.terra.boot.terraform.utils.SystemCmdResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** An executor for terraform. */
@Slf4j
@Component
public class TerraformExecutor {

    private static final String TF_VARS_FILE_NAME = "variables.tfvars.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private final SystemCmd systemCmd;

    private final boolean isStdoutStdErrLoggingEnabled;

    private final String customTerraformBinary;

    private final String terraformLogLevel;

    /**
     * Constructor for the TerraformExecutor bean.
     *
     * @param systemCmd SystemCmd bean
     * @param isStdoutStdErrLoggingEnabled value of `log.terraform.stdout.stderr` property
     * @param customTerraformBinary value of `terraform.binary.location` property
     * @param terraformLogLevel value of `terraform.log.level` property
     */
    @Autowired
    public TerraformExecutor(
            SystemCmd systemCmd,
            @Value("${log.terraform.stdout.stderr:true}") boolean isStdoutStdErrLoggingEnabled,
            @Value("${terraform.binary.location}") String customTerraformBinary,
            @Value("${terraform.log.level}") String terraformLogLevel) {
        this.systemCmd = systemCmd;
        this.customTerraformBinary = customTerraformBinary;
        this.isStdoutStdErrLoggingEnabled = isStdoutStdErrLoggingEnabled;
        this.terraformLogLevel = terraformLogLevel;
    }

    /** Terraform executes init, plan and destroy commands. */
    public SystemCmdResult tfDestroy(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        tfPlan(executorPath, variables, envVariables, taskWorkspace);
        SystemCmdResult applyResult =
                tfDestroyCommand(executorPath, variables, envVariables, taskWorkspace);
        if (!applyResult.isCommandSuccessful()) {
            log.error("TFExecutor.tfDestroy failed.");
            throw new TerraformExecutorException(
                    "TFExecutor.tfDestroy failed.", applyResult.getCommandStdError());
        }
        return applyResult;
    }

    /** Terraform executes init, plan and apply commands. */
    public SystemCmdResult tfApply(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        tfPlan(executorPath, variables, envVariables, taskWorkspace);
        SystemCmdResult applyResult =
                tfApplyCommand(executorPath, variables, envVariables, taskWorkspace);
        if (!applyResult.isCommandSuccessful()) {
            log.error("TFExecutor.tfApply failed.");
            throw new TerraformExecutorException(
                    "TFExecutor.tfApply failed.", applyResult.getCommandStdError());
        }
        return applyResult;
    }

    /** Terraform executes init and plan commands. */
    public SystemCmdResult tfPlan(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        tfInit(executorPath, taskWorkspace);
        SystemCmdResult planResult =
                tfPlanCommand(executorPath, variables, envVariables, taskWorkspace);
        if (!planResult.isCommandSuccessful()) {
            log.error("TFExecutor.tfPlan failed.");
            throw new TerraformExecutorException(
                    "TFExecutor.tfPlan failed.", planResult.getCommandStdError());
        }
        return planResult;
    }

    /** Method to execute terraform plan and get the plan as a json string. */
    public String getTerraformPlanAsJson(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        tfInit(executorPath, taskWorkspace);
        SystemCmdResult tfPlanResult =
                executeWithVariables(
                        new StringBuilder(
                                getTerraformCommand(
                                        executorPath,
                                        "plan -input=false -no-color --out tfplan.binary ")),
                        variables,
                        envVariables,
                        taskWorkspace);
        if (!tfPlanResult.isCommandSuccessful()) {
            log.error("TFExecutor.tfPlan failed.");
            throw new TerraformExecutorException(
                    "TFExecutor.tfPlan failed.", tfPlanResult.getCommandStdError());
        }
        SystemCmdResult planJsonResult =
                execute(
                        getTerraformCommand(executorPath, "show -json tfplan.binary"),
                        taskWorkspace,
                        envVariables);
        if (!planJsonResult.isCommandSuccessful()) {
            log.error("Reading Terraform plan as JSON failed.");
            throw new TerraformExecutorException(
                    "Reading Terraform plan as JSON failed.", planJsonResult.getCommandStdError());
        }
        return planJsonResult.getCommandStdOutput();
    }

    /** Terraform executes the init command. */
    public SystemCmdResult tfValidate(String executorPath, String taskWorkspace) {
        tfInit(executorPath, taskWorkspace);
        return tfValidateCommand(executorPath, taskWorkspace);
    }

    /** Terraform executes the init command. */
    public void tfInit(String executorPath, String taskWorkspace) {
        SystemCmdResult initResult = tfInitCommand(executorPath, taskWorkspace);
        if (!initResult.isCommandSuccessful()) {
            log.error("TFExecutor.tfInit failed.");
            throw new TerraformExecutorException(
                    "TFExecutor.tfInit failed.", initResult.getCommandStdError());
        }
    }

    /**
     * Executes terraform init command.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult tfInitCommand(String executorPath, String taskWorkspace) {
        return execute(
                getTerraformCommand(executorPath, "init -no-color"),
                taskWorkspace,
                new HashMap<>());
    }

    /**
     * Executes terraform validate command.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult tfValidateCommand(String executorPath, String taskWorkspace) {
        return execute(
                getTerraformCommand(executorPath, "validate -json -no-color"),
                taskWorkspace,
                new HashMap<>());
    }

    /**
     * Executes terraform plan command.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult tfPlanCommand(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        return executeWithVariables(
                new StringBuilder(
                        getTerraformCommand(executorPath, "plan -input=false -no-color ")),
                variables,
                envVariables,
                taskWorkspace);
    }

    /**
     * Executes terraform apply command.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult tfApplyCommand(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        return executeWithVariables(
                new StringBuilder(
                        getTerraformCommand(
                                executorPath, "apply -auto-approve -input=false -no-color ")),
                variables,
                envVariables,
                taskWorkspace);
    }

    /**
     * Executes terraform destroy command.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult tfDestroyCommand(
            String executorPath,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        return executeWithVariables(
                new StringBuilder(executorPath + " destroy -auto-approve -input=false -no-color "),
                variables,
                envVariables,
                taskWorkspace);
    }

    /**
     * Executes terraform commands with parameters.
     *
     * @return Returns result of SystemCmd executed.
     */
    private SystemCmdResult executeWithVariables(
            StringBuilder command,
            Map<String, Object> variables,
            Map<String, String> envVariables,
            String taskWorkspace) {
        createVariablesFile(variables, taskWorkspace);
        command.append(" -var-file=");
        command.append(TF_VARS_FILE_NAME);
        SystemCmdResult systemCmdResult = execute(command.toString(), taskWorkspace, envVariables);
        cleanUpVariablesFile(taskWorkspace);
        return systemCmdResult;
    }

    /**
     * Executes terraform commands.
     *
     * @return SystemCmdResult
     */
    private SystemCmdResult execute(
            String cmd, String taskWorkspace, @NonNull Map<String, String> envVariables) {
        envVariables.putAll(getTerraformLogConfig());
        return this.systemCmd.execute(
                cmd, taskWorkspace, this.isStdoutStdErrLoggingEnabled, envVariables);
    }

    private String getTerraformCommand(String executorPath, String terraformArguments) {
        if (Objects.isNull(this.customTerraformBinary) || this.customTerraformBinary.isBlank()) {
            return executorPath + " " + terraformArguments;
        }
        return this.customTerraformBinary + " " + terraformArguments;
    }

    private Map<String, String> getTerraformLogConfig() {
        return Collections.singletonMap("TF_LOG", this.terraformLogLevel);
    }

    private void createVariablesFile(Map<String, Object> variables, String taskWorkspace) {
        try {
            log.info("creating variables file");
            File varFile = new File(taskWorkspace, TF_VARS_FILE_NAME);
            OBJECT_MAPPER.writeValue(varFile, variables);
        } catch (IOException ioException) {
            throw new TerraformExecutorException(
                    "Creating variables file failed", ioException.getMessage());
        }
    }

    private void cleanUpVariablesFile(String taskWorkspace) {
        File file = new File(taskWorkspace, TF_VARS_FILE_NAME);
        try {
            log.info("cleaning up variables file");
            Files.deleteIfExists(file.toPath());
        } catch (IOException ioException) {
            log.error("Cleanup of variables file failed", ioException);
        }
    }
}
