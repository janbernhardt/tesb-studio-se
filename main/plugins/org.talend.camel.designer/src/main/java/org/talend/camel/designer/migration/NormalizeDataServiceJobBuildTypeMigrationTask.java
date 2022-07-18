package org.talend.camel.designer.migration;

import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import org.talend.commons.exception.ExceptionHandler;
import org.talend.commons.exception.PersistenceException;
import org.talend.core.GlobalServiceRegister;
import org.talend.core.model.components.filters.IComponentFilter;
import org.talend.core.model.components.filters.NameComponentFilter;
import org.talend.core.model.properties.Item;
import org.talend.designer.core.model.utils.emf.talendfile.NodeType;
import org.talend.designer.core.model.utils.emf.talendfile.ProcessType;
import org.talend.migration.MigrationReportRecorder;
import org.talend.repository.model.IProxyRepositoryFactory;
import org.talend.repository.model.IRepositoryService;

public class NormalizeDataServiceJobBuildTypeMigrationTask extends AbstractDataServiceJobMigrationTask {

    private static final String[] ESB_COMPONENTS = { "tESBConsumer", "tRESTClient", "tRESTRequest" };
    private static final String BUILD_TYPE_PROPERTY = "BUILD_TYPE";

    private static final String BUILD_TYPE_OSGI = "OSGI";
    private static final String BUILD_TYPE_ROUTE = "ROUTE";

    /*
    * (non-Javadoc)
    *
    * @see org.talend.migration.IMigrationTask#getOrder()
    */
    @Override
    public Date getOrder() {
        GregorianCalendar gc = new GregorianCalendar(2021, 7, 25, 12, 0, 0);
        return gc.getTime();
    }

    /*
    * (non-Javadoc)
    *
    * @see
    * org.talend.core.model.migration.AbstractDataserviceMigrationTask#execute(org
    * .talend.core.model.properties.Item)
    */
    @SuppressWarnings("unchecked")
    @Override
    public ExecutionResult execute(Item item) {
        final ProcessType processType = getProcessType(item);

        boolean modified = false;
        for (String name : ESB_COMPONENTS) {
            IComponentFilter filter = new NameComponentFilter(name);

            List<NodeType> c = searchComponent(processType, filter);

            if (!c.isEmpty()) {
                Object buildType = item.getProperty().getAdditionalProperties().get(BUILD_TYPE_PROPERTY);
                if (BUILD_TYPE_ROUTE.equals(buildType) || null == buildType) {
                    item.getProperty().getAdditionalProperties().put(BUILD_TYPE_PROPERTY, BUILD_TYPE_OSGI);
                    try {
                        save(item);
                        modified |= true;
                        generateReportRecord(new MigrationReportRecorder(this,
                                MigrationReportRecorder.MigrationOperationType.MODIFY, item, null, "Build Type",
                                BUILD_TYPE_ROUTE, BUILD_TYPE_OSGI));
                     } catch (PersistenceException e) {
                        ExceptionHandler.process(e);
                        return ExecutionResult.FAILURE;
                     }
                     return ExecutionResult.SUCCESS_NO_ALERT;
                }
            }
        }

        if (modified) {
            return ExecutionResult.SUCCESS_NO_ALERT;
        } else {
            return ExecutionResult.NOTHING_TO_DO;
        }
    }

    public static List<NodeType> searchComponent(ProcessType processType, IComponentFilter filter) {
        List<NodeType> list = new ArrayList<NodeType>();
        if (filter == null || processType == null) {
            return list;
        }

        for (Object o : processType.getNode()) {
            if (filter.accept((NodeType) o)) {
                list.add((NodeType) o);
            }
        }
        return list;
    }

    public void save(Item item) throws PersistenceException {
        IRepositoryService service = (IRepositoryService) GlobalServiceRegister.getDefault()
            .getService(IRepositoryService.class);
            IProxyRepositoryFactory factory = service.getProxyRepositoryFactory();
            factory.save(item, true);
    }

}
