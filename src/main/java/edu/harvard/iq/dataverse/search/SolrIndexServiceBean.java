package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.DvObjectServiceBean;
import edu.harvard.iq.dataverse.IndexServiceBean;
import edu.harvard.iq.dataverse.util.SystemConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.inject.Named;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;

@Named
@Stateless
public class SolrIndexServiceBean {

    private static final Logger logger = Logger.getLogger(SolrIndexServiceBean.class.getCanonicalName());

    @EJB
    SystemConfig systemConfig;
    @EJB
    DvObjectServiceBean dvObjectService;
    @EJB
    SearchPermissionsServiceBean searchPermissionsService;
    @EJB
    DataverseServiceBean dataverseService;

    public List<DvObjectSolrDoc> determineSolrDocs(Long dvObjectId) {
        List<DvObjectSolrDoc> emptyList = new ArrayList<>();
        List<DvObjectSolrDoc> solrDocs = emptyList;
        DvObject dvObject = dvObjectService.findDvObject(dvObjectId);
        if (dvObject == null) {
            return emptyList;
        }
        if (dvObject.isInstanceofDataverse()) {
            DvObjectSolrDoc dataverseSolrDoc = constructDataverseSolrDoc((Dataverse) dvObject);
            solrDocs.add(dataverseSolrDoc);
        } else if (dvObject.isInstanceofDataset()) {
            List<DvObjectSolrDoc> datasetSolrDocs = constructDatasetSolrDocs((Dataset) dvObject);
            solrDocs.addAll(datasetSolrDocs);
        } else if (dvObject.isInstanceofDataFile()) {
            List<DvObjectSolrDoc> fileSolrDocs = constructDatafileSolrDocs((DataFile) dvObject);
            solrDocs.addAll(fileSolrDocs);
        } else {
            logger.info("Unexpected DvObject: " + dvObject.getClass().getName());
        }
        return solrDocs;
    }

    /**
     * @todo should this method return a List? The equivalent methods for
     * datasets and files return lists.
     */
    private DvObjectSolrDoc constructDataverseSolrDoc(Dataverse dataverse) {
        List<String> perms = searchPermissionsService.findDataversePerms(dataverse);
        DvObjectSolrDoc dvDoc = new DvObjectSolrDoc(dataverse.getId().toString(), IndexServiceBean.solrDocIdentifierDataverse + dataverse.getId(), dataverse.getName(), perms);
        return dvDoc;
    }

    private List<DvObjectSolrDoc> constructDatasetSolrDocs(Dataset dataset) {
        List<DvObjectSolrDoc> emptyList = new ArrayList<>();
        List<DvObjectSolrDoc> solrDocs = emptyList;
        Map<DatasetVersion.VersionState, Boolean> desiredCards = searchPermissionsService.getDesiredCards(dataset);
        for (DatasetVersion version : datasetVersionsToBuildCardsFor(dataset)) {
            boolean cardShouldExist = desiredCards.get(version.getVersionState());
            if (cardShouldExist) {
                DvObjectSolrDoc datasetSolrDoc = makeDatasetSolrDoc(version);
                solrDocs.add(datasetSolrDoc);
            }
        }
        return solrDocs;
    }

    /**
     * @todo In this method should we really piggyback off the output of
     * constructDatasetSolrDocs like this? It was the easiest thing to get
     * working quickly.
     */
    private List<DvObjectSolrDoc> constructDatafileSolrDocs(DataFile dataFile) {
        List<DvObjectSolrDoc> datafileSolrDocs = new ArrayList<>();
        List<DvObjectSolrDoc> datasetSolrDocs = constructDatasetSolrDocs(dataFile.getOwner());
        for (DvObjectSolrDoc dataset : datasetSolrDocs) {
            logger.info(dataset.toString());
            String datasetSolrId = dataset.getSolrId();
            /**
             * @todo We should probably get away from the assumption that
             * endings always end with underscore such as "_draft".
             */
            String indicatorOfPublishedSolrId = ".*_[0-9]+$";
            String ending = "";
            if (!datasetSolrId.matches(indicatorOfPublishedSolrId)) {
                ending = datasetSolrId.substring(datasetSolrId.lastIndexOf('_'));
            }
            String fileSolrId = IndexServiceBean.solrDocIdentifierFile + dataFile.getId() + ending;
            /**
             * @todo We should show the filename for this version of the file.
             * Also, go look at all the complicated logic about
             * filenameCompleteFinal in IndexServiceBean!
             */
            String name = dataFile.getDisplayName();
            DvObjectSolrDoc dataFileSolrDoc = new DvObjectSolrDoc(dataFile.getId().toString(), fileSolrId, name, dataset.getPermissions());
            datafileSolrDocs.add(dataFileSolrDoc);
        }
        return datafileSolrDocs;
    }

    private List<DatasetVersion> datasetVersionsToBuildCardsFor(Dataset dataset) {
        List<DatasetVersion> datasetVersions = new ArrayList<>();
        DatasetVersion latest = dataset.getLatestVersion();
        if (latest != null) {
            datasetVersions.add(latest);
        }
        DatasetVersion released = dataset.getReleasedVersion();
        if (released != null) {
            datasetVersions.add(released);
        }
        return datasetVersions;
    }

    private DvObjectSolrDoc makeDatasetSolrDoc(DatasetVersion version) {
        String solrIdStart = IndexServiceBean.solrDocIdentifierDataset + version.getDataset().getId().toString();
        String solrIdEnd = getDatasetSolrIdEnding(version.getVersionState());
        String solrId = solrIdStart + solrIdEnd;
        String name = version.getTitle();
        List<String> perms = searchPermissionsService.findDatasetVersionPerms(version);
        return new DvObjectSolrDoc(version.getDataset().getId().toString(), solrId, name, perms);
    }

    private String getDatasetSolrIdEnding(DatasetVersion.VersionState versionState) {
        if (versionState.equals(DatasetVersion.VersionState.RELEASED)) {
            return "";
        } else if (versionState.equals(DatasetVersion.VersionState.DRAFT)) {
            return IndexServiceBean.draftSuffix;
        } else if (versionState.equals(DatasetVersion.VersionState.DEACCESSIONED)) {
            return IndexServiceBean.deaccessionedSuffix;
        } else {
            return "_unexpectedDatasetVersion";
        }
    }

    public IndexResponse indexAllPermissions() {
        Collection<SolrInputDocument> docs = new ArrayList<>();

        List<DvObjectSolrDoc> definitionPoints = new ArrayList<>();
        for (DvObject dvObject : dvObjectService.findAll()) {
            definitionPoints.addAll(determineSolrDocs(dvObject.getId()));
        }

        for (DvObjectSolrDoc dvObjectSolrDoc : definitionPoints) {
            SolrInputDocument solrInputDocument = createSolrDoc(dvObjectSolrDoc);
            docs.add(solrInputDocument);
        }
        try {
            persistToSolr(docs);
            /**
             * @todo Do we need a separate permissionIndexTime timestamp?
             * Probably. Update it here.
             */
            return new IndexResponse("indexed all permissions");
        } catch (SolrServerException | IOException ex) {
            return new IndexResponse("problem indexing");
        }

    }

    public IndexResponse indexPermissionsForOneDvObject(long dvObjectId) {
        Collection<SolrInputDocument> docs = new ArrayList<>();

        List<DvObjectSolrDoc> definitionPoints = determineSolrDocs(dvObjectId);

        for (DvObjectSolrDoc dvObjectSolrDoc : definitionPoints) {
            SolrInputDocument solrInputDocument = createSolrDoc(dvObjectSolrDoc);
            docs.add(solrInputDocument);
        }
        try {
            persistToSolr(docs);
            /**
             * @todo Do we need a separate permissionIndexTime timestamp?
             * Probably. Update it here.
             */
            return new IndexResponse("attempted to index permissions for DvObject " + dvObjectId);
        } catch (SolrServerException | IOException ex) {
            return new IndexResponse("problem indexing");
        }

    }

    private SolrInputDocument createSolrDoc(DvObjectSolrDoc dvObjectSolrDoc) {
        SolrInputDocument solrInputDocument = new SolrInputDocument();
        solrInputDocument.addField(SearchFields.ID, dvObjectSolrDoc.getSolrId() + IndexServiceBean.discoverabilityPermissionSuffix);
        solrInputDocument.addField(SearchFields.DEFINITION_POINT, dvObjectSolrDoc.getSolrId());
        solrInputDocument.addField(SearchFields.DEFINITION_POINT_DVOBJECT_ID, dvObjectSolrDoc.getDvObjectId());
        solrInputDocument.addField(SearchFields.DISCOVERABLE_BY, dvObjectSolrDoc.getPermissions());
        return solrInputDocument;
    }

    private void persistToSolr(Collection<SolrInputDocument> docs) throws SolrServerException, IOException {
        if (docs.isEmpty()) {
            /**
             * @todo Throw an exception here? "DvObject id 9999 does not exist."
             */
            return;
        }
        SolrServer solrServer = new HttpSolrServer("http://" + systemConfig.getSolrHostColonPort() + "/solr");
        /**
         * @todo Do something with these responses from Solr.
         */
        UpdateResponse addResponse = solrServer.add(docs);
        UpdateResponse commitResponse = solrServer.commit();
    }

    public IndexResponse indexPermissionsOnSelfAndChildren(DvObject definitionPoint) {
        List<Long> dvObjectsToReindexPermissionsFor = new ArrayList<>();
        /**
         * @todo Re-indexing the definition point itself seems to be necessary
         * for revoke but not necessarily grant.
         */
        dvObjectsToReindexPermissionsFor.add(definitionPoint.getId());
        SolrServer solrServer = new HttpSolrServer("http://" + systemConfig.getSolrHostColonPort() + "/solr");
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("*");
        solrQuery.setRows(Integer.SIZE);
        if (definitionPoint.isInstanceofDataverse()) {
            Dataverse dataverse = (Dataverse) definitionPoint;

            String dataversePath = dataverseService.determineDataversePath(dataverse);
            String filterDownToSubtree = SearchFields.SUBTREE + ":\"" + dataversePath + "\"";

            solrQuery.addFilterQuery(filterDownToSubtree);
        }

        QueryResponse queryResponse = null;
        try {
            queryResponse = solrServer.query(solrQuery);
            if (queryResponse != null) {
                for (SolrDocument solrDoc : queryResponse.getResults()) {
                    try {
                        long dvObjectId = (Long) solrDoc.getFieldValue(SearchFields.ENTITY_ID);
                        dvObjectsToReindexPermissionsFor.add(dvObjectId);
                    } catch (NullPointerException ex) {
                        /**
                         * @todo why are we getting an NPE with
                         * rebuild-and-test?
                         */
                        logger.info("caught NPE");
                    }
                }
            }

        } catch (SolrServerException | HttpSolrServer.RemoteSolrException ex) {

        }

        for (Long dvObjectId : dvObjectsToReindexPermissionsFor) {
            /**
             * @todo do something with this response
             */
            IndexResponse indexResponse = indexPermissionsForOneDvObject(dvObjectId);
        }
        return new IndexResponse("Number of dvObject permissions indexed: " + dvObjectsToReindexPermissionsFor.size());
    }

}
