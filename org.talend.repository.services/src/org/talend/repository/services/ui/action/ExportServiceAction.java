package org.talend.repository.services.ui.action;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.talend.commons.exception.PersistenceException;
import org.talend.commons.utils.io.FilesUtils;
import org.talend.core.model.repository.ERepositoryObjectType;
import org.talend.core.model.repository.IRepositoryViewObject;
import org.talend.core.repository.constants.FileConstants;
import org.talend.core.repository.model.ProxyRepositoryFactory;
import org.talend.designer.publish.core.SaveAction;
import org.talend.designer.publish.core.models.BundleModel;
import org.talend.designer.publish.core.models.FeaturesModel;
import org.talend.repository.model.IRepositoryNode.ENodeType;
import org.talend.repository.model.IRepositoryNode.EProperties;
import org.talend.repository.model.RepositoryNode;
import org.talend.repository.services.model.services.ServiceConnection;
import org.talend.repository.services.model.services.ServiceItem;
import org.talend.repository.services.model.services.ServiceOperation;
import org.talend.repository.services.model.services.ServicePort;
import org.talend.repository.services.ui.scriptmanager.ServiceExportManager;
import org.talend.repository.services.utils.ESBRepositoryNodeType;
import org.talend.repository.services.utils.WSDLUtils;
import org.talend.repository.ui.utils.ZipToFile;
import org.talend.repository.ui.wizards.exportjob.action.JobExportAction;
import org.talend.repository.ui.wizards.exportjob.scriptsmanager.JobScriptsManager;

public class ExportServiceAction implements IRunnableWithProgress {

    private List<RepositoryNode> nodes = new ArrayList<RepositoryNode>();

    private String serviceVersion;

    protected ServiceExportManager serviceManager;

    private IFile serviceWsdl;

    private final Map<ServicePort, Map<String, String>> ports = new HashMap<ServicePort, Map<String, String>>();

    private String serviceName;

    private String groupId;

    private static String JOB_CONTROLLER_FEATURE = "talend-job-controller";

    private static String JOB_CONTROLLER_VERSION = "[5,6)";

    private final IRepositoryViewObject serviceViewObject;

    private ServiceConnection serviceConnection;

    public ExportServiceAction(RepositoryNode node, String targetPath) throws InvocationTargetException {
        if (node.getType() == ENodeType.REPOSITORY_ELEMENT
                && node.getProperties(EProperties.CONTENT_TYPE) == ESBRepositoryNodeType.SERVICES) {
            this.serviceViewObject = node.getObject();
            init(targetPath);
        } else {
            IllegalArgumentException e = new IllegalArgumentException("provided node is not service node");
            throw new InvocationTargetException(e);
        }
    }

    public ExportServiceAction(IRepositoryViewObject viewObject, String targetPath) throws InvocationTargetException {
        this.serviceViewObject = viewObject;
        init(targetPath);
    }

    private void init(String targetPath) throws InvocationTargetException {
        serviceName = serviceViewObject.getLabel();
        serviceVersion = serviceViewObject.getVersion();
        serviceWsdl = WSDLUtils.getWsdlFile(serviceViewObject);

        ServiceItem serviceItem = (ServiceItem) serviceViewObject.getProperty().getItem();
        serviceConnection = (ServiceConnection) serviceItem.getConnection();
        EList<ServicePort> listPort = serviceConnection.getServicePort();
        try {
            for (ServicePort port : listPort) {
                List<ServiceOperation> listOperation = port.getServiceOperation();
                Map<String, String> operations = new HashMap<String, String>(listOperation.size());
                for (ServiceOperation operation : listOperation) {
                    if (operation.getReferenceJobId() != null && !operation.getReferenceJobId().equals("")) {
                        String operationName = operation.getName();
                        RepositoryNode jobNode = getJobRepositoryNode(operation.getReferenceJobId());
                        String jobName = jobNode.getObject().getLabel();
                        operations.put(operationName, jobName);
                        nodes.add(jobNode);
                    }
                }
                ports.put(port, operations);
            }
        } catch (PersistenceException e) {
            throw new InvocationTargetException(e);
        }

        final String suffix = FileConstants.KAR_FILE_SUFFIX;
        if (targetPath.endsWith(suffix)) {
            targetPath = targetPath.substring(0, targetPath.length() - suffix.length());
        }
        this.serviceManager = new ServiceExportManager();
        serviceManager.setDestinationPath(targetPath);
    }

    private RepositoryNode getJobRepositoryNode(String jobId) throws PersistenceException {
        List<IRepositoryViewObject> jobs = ProxyRepositoryFactory.getInstance().getAll(ERepositoryObjectType.PROCESS);
        for (IRepositoryViewObject job : jobs) {
            if (job.getId().equals(jobId)) {
                return new RepositoryNode(job, null, ENodeType.REPOSITORY_ELEMENT);
            }
        }
        return null;
    }

    public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
        final String destinationPath = serviceManager.getDestinationPath();
        try {
            String directoryName = serviceManager.getRootFolderName(destinationPath);
            Map<String, String> bundles = new HashMap<String, String>();
            for (RepositoryNode node : nodes) {
                JobScriptsManager manager = serviceManager.getJobManager(node, getGroupId(), getServiceVersion());
                JobExportAction job = new JobExportAction(Collections.singletonList(node), node.getObject().getVersion(),
                        getBundleVersion(), manager, directoryName, "Service");
                job.run(monitor);
                bundles.put(serviceManager.getNodeLabel(node), manager.getDestinationPath());
            }
            try {
                final String artefactName = getServiceName() + "-control-bundle";
                bundles.put(artefactName, generateControlBundle(getGroupId(), artefactName));
                processFeature(generateFeature(bundles));
                processFinalResult(destinationPath);
            } catch (Exception e) {
                throw new InvocationTargetException(e);
            }
        } finally {
            FilesUtils.removeFolder(destinationPath, true);
        }
    }

    private String generateControlBundle(String groupId, String artefactName) throws IOException, CoreException {
        File temp = new File(serviceManager.getDestinationPath(), "temp");
        File metaInf = new File(temp, FileConstants.META_INF_FOLDER_NAME);
        metaInf.mkdirs();
        // manifest
        FileOutputStream out = new FileOutputStream(new File(metaInf, FileConstants.MANIFEST_MF_FILE_NAME));
        serviceManager.getManifest(artefactName, getBundleVersion()).write(out);
        out.close();
        // wsdl
        File wsdl = new File(temp, serviceWsdl.getName());
        FilesUtils.copyFile(serviceWsdl.getLocation().toFile(), wsdl);
        // spring
        File spring = new File(metaInf, "spring");
        spring.mkdirs();
        serviceManager.createSpringBeans(new File(spring, "beans.xml").getAbsolutePath(), ports, serviceConnection, wsdl,
                getServiceName());
        String fileName = artefactName + "-" + getServiceVersion() + FileConstants.JAR_FILE_SUFFIX;
        File file = new File(serviceManager.getFilePath(groupId, artefactName, getServiceVersion()), fileName);
        try {
            ZipToFile.zipFile(temp.getAbsolutePath(), file.getAbsolutePath());
            FilesUtils.removeFolder(temp, true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        return file.getAbsolutePath();
    }

    private FeaturesModel generateFeature(Map<String, String> bundles) throws IOException {
        FeaturesModel feature = new FeaturesModel(getGroupId(), getServiceName(), getServiceVersion());
        for (Map.Entry<String, String> entry : bundles.entrySet()) {
            File jarFile = new File(entry.getValue());
            BundleModel model = new BundleModel(jarFile, getGroupId(), entry.getKey(), getServiceVersion());
            feature.addSubBundle(model);
        }
        // <feature version='[5,6)'>talend-job-controller</feature>
        feature.addSubFeature(JOB_CONTROLLER_FEATURE, JOB_CONTROLLER_VERSION);
        feature.setConfigName(serviceViewObject.getLabel());
        feature.setContexts(ContextNodeRetriever.getContextsMap((ServiceItem) serviceViewObject.getProperty().getItem()));
        return feature;
    }

    protected void processFeature(FeaturesModel feature) throws IOException {
        String artefactName = getServiceName() + "-feature";
        File filePath = serviceManager.getFilePath(getGroupId(), artefactName, getServiceVersion());
        String fileName = artefactName + "-" + getServiceVersion() + "-feature.xml";
        String featureFilePath = new File(filePath, fileName).getAbsolutePath();
        SaveAction.saveFeature(featureFilePath, feature);
    }

    protected void processFinalResult(String destinationPath) throws IOException {
        try {
            File destination = new File(destinationPath);
            String s = destination.getAbsolutePath() + FileConstants.KAR_FILE_SUFFIX;
            ZipToFile.zipFile(destinationPath, s);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getGroupId() {
        if (null == groupId) {
            try {
                groupId = getGroupId(WSDLUtils.getDefinition(serviceWsdl.getLocation().toOSString()).getTargetNamespace(),
                        getServiceName());
            } catch (CoreException e) {
                throw new RuntimeException(e);
            }
        }
        return groupId;
    }

    public String getBundleVersion() {
        return serviceVersion;
    }

    private final String getGroupId(String serviceNS, String serviceName) {
        String schemeId;
        try {
            schemeId = new URI(serviceNS).getScheme() + "://";
        } catch (URISyntaxException e1) {
            schemeId = "http://";
        }
        String servNS = serviceNS.replace(schemeId, "");
        if (!servNS.endsWith("/")) {
            servNS += "/";
        }
        return (servNS + serviceName).replace('/', '.');
    }

}
