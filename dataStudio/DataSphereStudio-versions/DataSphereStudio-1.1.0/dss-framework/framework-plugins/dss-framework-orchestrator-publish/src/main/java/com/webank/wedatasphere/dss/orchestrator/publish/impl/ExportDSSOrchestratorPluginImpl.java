/*
 * Copyright 2019 WeBank
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.webank.wedatasphere.dss.orchestrator.publish.impl;

import com.webank.wedatasphere.dss.common.entity.IOType;
import com.webank.wedatasphere.dss.common.exception.DSSErrorException;
import com.webank.wedatasphere.dss.common.label.DSSLabel;
import com.webank.wedatasphere.dss.common.utils.DSSExceptionUtils;
import com.webank.wedatasphere.dss.common.utils.IoUtils;
import com.webank.wedatasphere.dss.common.utils.MapUtils;
import com.webank.wedatasphere.dss.contextservice.service.ContextService;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorInfo;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorVersion;
import com.webank.wedatasphere.dss.orchestrator.common.ref.OrchestratorRefConstant;
import com.webank.wedatasphere.dss.orchestrator.core.DSSOrchestrator;
import com.webank.wedatasphere.dss.orchestrator.core.exception.DSSOrchestratorErrorException;
import com.webank.wedatasphere.dss.orchestrator.core.plugin.AbstractDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.core.service.BMLService;
import com.webank.wedatasphere.dss.orchestrator.core.utils.OrchestratorUtils;
import com.webank.wedatasphere.dss.orchestrator.db.dao.OrchestratorMapper;
import com.webank.wedatasphere.dss.orchestrator.loader.OrchestratorManager;
import com.webank.wedatasphere.dss.orchestrator.publish.ExportDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.publish.io.export.MetaExportService;
import com.webank.wedatasphere.dss.orchestrator.publish.utils.OrchestrationDevelopmentOperationUtils;
import com.webank.wedatasphere.dss.standard.app.development.operation.RefCopyOperation;
import com.webank.wedatasphere.dss.standard.app.development.operation.RefExportOperation;
import com.webank.wedatasphere.dss.standard.app.development.ref.*;
import com.webank.wedatasphere.dss.standard.app.development.service.RefCRUDService;
import com.webank.wedatasphere.dss.standard.app.development.service.RefExportService;
import com.webank.wedatasphere.dss.standard.app.development.standard.DevelopmentIntegrationStandard;
import com.webank.wedatasphere.dss.standard.app.sso.Workspace;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.webank.wedatasphere.dss.common.utils.ZipHelper.zipExportProject;


@Component
public class ExportDSSOrchestratorPluginImpl extends AbstractDSSOrchestratorPlugin implements ExportDSSOrchestratorPlugin {

    static final String DEFAULT_ORC_NAME = "default_orc";

    @Autowired
    private BMLService bmlService;
    @Autowired
    private OrchestratorMapper orchestratorMapper;
    @Autowired
    private MetaExportService metaExportService;
    @Autowired
    private ContextService contextService;
    @Autowired
    private OrchestratorManager orchestratorManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exportOrchestrator(String userName, Long orchestratorId, Long orcVersionId, String projectName,
                                                  List<DSSLabel> dssLabels, boolean addOrcVersion, Workspace workspace) throws DSSErrorException {
        //1、导出info信息
        if (orcVersionId == null || orcVersionId < 0){
            LOGGER.info("orchestratorVersionId is {}.", orcVersionId);
            //最简单的就是通过orcId来找到最新的versionId
            orcVersionId = orchestratorMapper.findLatestOrcVersionId(orchestratorId);
        }
        DSSOrchestratorVersion dssOrchestratorVersion = orchestratorMapper.getOrchestratorVersion(orcVersionId);
        DSSOrchestratorInfo dssOrchestratorInfo = orchestratorMapper.getOrchestrator(dssOrchestratorVersion.getOrchestratorId());
        if (dssOrchestratorInfo != null) {
            String orcExportSaveBasePath = IoUtils.generateIOPath(userName, DEFAULT_ORC_NAME, "");
            try {
                Files.createDirectories(Paths.get(orcExportSaveBasePath).toAbsolutePath().normalize());
                //标记当前导出为project导出
                IoUtils.generateIOType(IOType.ORCHESTRATOR, orcExportSaveBasePath);
                //标记当前导出环境env
                IoUtils.generateIOEnv(orcExportSaveBasePath);
                metaExportService.export(dssOrchestratorInfo, orcExportSaveBasePath);
            } catch (IOException e) {
                LOGGER.error("Failed to export metaInfo in orchestrator server for orc({}) in version {}.", orchestratorId, orcVersionId, e);
                DSSExceptionUtils.dealErrorException(60099, "Failed to export metaInfo in orchestrator server.", e, DSSOrchestratorErrorException.class);
            }
            LOGGER.info("{} 开始导出Orchestrator: {} 版本ID为: {}.", userName, dssOrchestratorInfo.getName(), orcVersionId);

            //2、导出第三方应用信息，如工作流、Visualis、Qualities
            DSSOrchestrator dssOrchestrator = orchestratorManager.getOrCreateOrchestrator(userName, workspace.getWorkspaceName(), dssOrchestratorInfo.getType(),
                    dssLabels);
            ExportResponseRef responseRef = OrchestrationDevelopmentOperationUtils.tryOrchestrationOperation(dssOrchestratorInfo, dssOrchestrator, userName,
                    workspace, dssLabels, DevelopmentIntegrationStandard::getRefExportService,
                    developmentService -> ((RefExportService) developmentService).getRefExportOperation(),
                    null,
                    projectRefRequestRef -> projectRefRequestRef.setProjectName(projectName).setRefProjectId(dssOrchestratorVersion.getProjectId()),
                    (developmentOperation, developmentRequestRef) -> {
                        RefJobContentRequestRef requestRef = (RefJobContentRequestRef) developmentRequestRef;
                        requestRef.setRefJobContent(MapUtils.newCommonMap(OrchestratorRefConstant.ORCHESTRATION_ID_KEY, dssOrchestratorVersion.getAppId()));
                        return ((RefExportOperation) developmentOperation).exportRef(requestRef);
                    }, "export");
            String resourceId = (String) responseRef.getResourceMap().get(ImportRequestRef.RESOURCE_ID_KEY);
            String version = (String) responseRef.getResourceMap().get(ImportRequestRef.RESOURCE_VERSION_KEY);
            bmlService.downloadToLocalPath(userName, resourceId, version, orcExportSaveBasePath + "orc_flow.zip");

            //打包导出工程
            String exportPath = zipExportProject(orcExportSaveBasePath);

            //3、打包新的zip包上传BML
            InputStream inputStream = bmlService.readLocalResourceFile(userName, exportPath);
            Map<String, Object> resultMap = bmlService.upload(userName, inputStream,
                    dssOrchestratorInfo.getName() + ".OrcExport", projectName);

            //4、判断导出后是否改变Orc的版本
            if (addOrcVersion) {
                Long orcIncreaseVersionId = orchestratorVersionIncrease(dssOrchestratorInfo.getId(),
                        userName, dssOrchestratorInfo.getComment(),
                        workspace, dssOrchestratorInfo, projectName, dssLabels);
                resultMap.put("orcVersionId", orcIncreaseVersionId);
            } else {
                resultMap.put("orcVersionId", orcVersionId);
            }
            return resultMap;
            //4、返回BML存储信息
        } else {
            throw new DSSErrorException(90038, "该Orchestrator的版本号不存在，请检查版本号是否正确.");
        }
    }

    @Override
    public Long orchestratorVersionIncrease(Long orcId,
                                            String userName,
                                            String comment,
                                            Workspace workspace,
                                            DSSOrchestratorInfo dssOrchestratorInfo,
                                            String projectName,
                                            List<DSSLabel> dssLabels) throws DSSErrorException {
        //对于导出来说,json需替换 subflowID
        DSSOrchestratorVersion dssOrchestratorVersion = orchestratorMapper.getLatestOrchestratorVersionByIdAndValidFlag(orcId,1);
        // TODO: 2020/3/25 set updator(userID 修改为userName后)
        dssOrchestratorVersion.setUpdateTime(new Date());
        Long oldOrcVersionId = dssOrchestratorVersion.getId();
        String oldOrcVersion = dssOrchestratorVersion.getVersion();
        dssOrchestratorVersion.setId(null);
        // 如果是project发布 ,version都是01,如果是工作流发布,version + 1
        dssOrchestratorVersion.setVersion(OrchestratorUtils.increaseVersion(oldOrcVersion));

        //发布的comment应该更新到上个版本,而当前最新版本的comment应该修改为release from..
        dssOrchestratorVersion.setComment(String.format("release from version %s", oldOrcVersion));
        //更新老版本的comment
        DSSOrchestratorVersion updateCommentVersion = new DSSOrchestratorVersion();
        updateCommentVersion.setId(oldOrcVersionId);
        String realComment = comment != null ? comment : "release comment";
        updateCommentVersion.setComment(realComment);
        if(StringUtils.isNotBlank(userName)){
            updateCommentVersion.setUpdater(userName);
            dssOrchestratorVersion.setUpdater(userName);
        }
        updateCommentVersion.setUpdateTime(new Date());
        orchestratorMapper.updateOrchestratorVersion(updateCommentVersion);

        //要求AppConn对应第三方应用拷贝一个新的app出来关联，如工作流，需要新建一个新的工作流进行关联。
        //1、生成上下文ContextId
        String contextId = contextService.createContextID(workspace.getWorkspaceName(), projectName, dssOrchestratorInfo.getName(), dssOrchestratorVersion.getVersion(), userName);
        dssOrchestratorVersion.setContextId(contextId);
        LOGGER.info("Create a new ContextId {} for orchestration {} to increase version to {}.", contextId, orcId, dssOrchestratorVersion.getVersion());
        DSSOrchestrator dssOrchestrator = orchestratorManager.getOrCreateOrchestrator(userName, workspace.getWorkspaceName(), dssOrchestratorInfo.getType(),
                dssLabels);
        RefJobContentResponseRef responseRef = OrchestrationDevelopmentOperationUtils.tryOrchestrationOperation(dssOrchestratorInfo, dssOrchestrator, userName,
                workspace, dssLabels, DevelopmentIntegrationStandard::getRefCRUDService,
                developmentService -> ((RefCRUDService) developmentService).getRefCopyOperation(),
                dssContextRequestRef -> dssContextRequestRef.setContextId(contextId),
                projectRefRequestRef -> projectRefRequestRef.setProjectName(projectName).setRefProjectId(dssOrchestratorVersion.getProjectId()),
                (developmentOperation, developmentRequestRef) -> {
                    CopyRequestRef requestRef = (CopyRequestRef) developmentRequestRef;
                    Map<String, Object> refJobContent = MapUtils.newCommonMap(OrchestratorRefConstant.ORCHESTRATION_ID_KEY, dssOrchestratorVersion.getAppId(),
                            OrchestratorRefConstant.ORCHESTRATION_DESCRIPTION, dssOrchestratorVersion.getComment());
                    requestRef.setNewVersion(dssOrchestratorVersion.getVersion()).setRefJobContent(refJobContent);
                    return ((RefCopyOperation) developmentOperation).copyRef(requestRef);
                }, "increase");
        dssOrchestratorVersion.setAppId((Long) responseRef.getRefJobContent().get(OrchestratorRefConstant.ORCHESTRATION_ID_KEY));
        dssOrchestratorVersion.setContent((String) responseRef.getRefJobContent().get(OrchestratorRefConstant.ORCHESTRATION_CONTENT_KEY));
        //update appConn node contextId
        dssOrchestratorVersion.setFormatContextId(contextId);
        orchestratorMapper.addOrchestratorVersion(dssOrchestratorVersion);
        return dssOrchestratorVersion.getId();
    }

    @Override
    public Long addVersionAfterPublish(String userName, Workspace workspace, Long orchestratorId,
                                       Long orcVersionId, String projectName,
                                       List<DSSLabel> dssLabels,String comment) throws DSSErrorException {
        //发布之后添加一个版本号
        if (orcVersionId == null || orcVersionId < 0) {
            LOGGER.info("orchestratorVersionId is {}", orcVersionId);
            //最简单的就是通过orcId来找到最新的versionId
            orcVersionId = orchestratorMapper.findLatestOrcVersionId(orchestratorId);
        }
        DSSOrchestratorVersion dssOrchestratorVersion = orchestratorMapper.getOrchestratorVersion(orcVersionId);
        DSSOrchestratorInfo dssOrchestratorInfo = orchestratorMapper.getOrchestrator(dssOrchestratorVersion.getOrchestratorId());
        Long orcIncreaseVersionId = orchestratorVersionIncrease(dssOrchestratorInfo.getId(),
                userName, comment,
                workspace, dssOrchestratorInfo, projectName, dssLabels);
        orcIncreaseVersionId = orcIncreaseVersionId == null ? 0L : orcIncreaseVersionId;
        return orcIncreaseVersionId;
    }
}
