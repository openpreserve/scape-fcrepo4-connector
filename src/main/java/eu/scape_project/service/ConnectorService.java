/**
 *
 */

package eu.scape_project.service;

import info.lc.xmlns.premis_v2.PremisComplexType;
import info.lc.xmlns.premis_v2.RightsComplexType;
import info.lc.xmlns.textmd_v3.TextMD;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.xml.bind.JAXBException;

import org.apache.commons.io.IOUtils;
import org.fcrepo.Datastream;
import org.fcrepo.FedoraObject;
import org.fcrepo.exception.InvalidChecksumException;
import org.fcrepo.rdf.SerializationUtils;
import org.fcrepo.services.DatastreamService;
import org.fcrepo.services.NodeService;
import org.fcrepo.services.ObjectService;
import org.fcrepo.session.SessionFactory;
import org.purl.dc.elements._1.ElementContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.books.gbs.GbsType;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.StmtIterator;

import edu.harvard.hul.ois.xml.ns.fits.fits_output.Fits;
import eu.scapeproject.model.BitStream;
import eu.scapeproject.model.File;
import eu.scapeproject.model.Identifier;
import eu.scapeproject.model.IntellectualEntity;
import eu.scapeproject.model.LifecycleState;
import eu.scapeproject.model.LifecycleState.State;
import eu.scapeproject.model.Representation;
import eu.scapeproject.util.ScapeMarshaller;
import gov.loc.audiomd.AudioType;
import gov.loc.marc21.slim.RecordType;
import gov.loc.mix.v20.Mix;
import gov.loc.videomd.VideoType;

@Component
public class ConnectorService {

    public final static String ENTITY_FOLDER = "objects/scape/entities";

    public final static String QUEUE_NODE = "/objects/scape/queue";

    //TODO: this should be taken from the UriInfo or at least from a bean config
    private final String fedoraUrl = "http://localhost:8080/rest";

    private final ScapeMarshaller marshaller;

    private static final Logger LOG = LoggerFactory
            .getLogger(ConnectorService.class);

    @Autowired
    private ObjectService objectService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private DatastreamService datastreamService;

    @Autowired
    private SessionFactory sessionFactory;

    private final java.io.File tempDirectory;

    public ConnectorService()
            throws JAXBException {
        marshaller = ScapeMarshaller.newInstance();
        tempDirectory =
                new java.io.File(System.getProperty("java.io.tmpdir") +
                        "/scape-connector-queue");
        if (!tempDirectory.exists()) {
            tempDirectory.mkdir();
        }
    }

    public IntellectualEntity
            fetchEntity(final Session session, final String id)
                    throws RepositoryException {

        final IntellectualEntity.Builder ie = new IntellectualEntity.Builder();
        ie.identifier(new Identifier(id));

        final String entityPath = "/" + ENTITY_FOLDER + "/" + id;
        final FedoraObject ieObject =
                this.objectService.getObject(session, entityPath);

        /* fetch the ie's metadata form the repo */
        ie.descriptive(fetchMetadata(session, entityPath + "/DESCRIPTIVE"));

        /* fetch the representations */
        final Model entityModel =
                SerializationUtils.unifyDatasetModel(ieObject
                        .getPropertiesDataset());

        /* find all the representations of this entity */
        final Resource parent =
                entityModel.createResource("info:fedora" + ieObject.getPath());
        final List<Representation> reps = new ArrayList<>();
        for (String repUri : getLiteralStrings(entityModel, parent,
                "http://scapeproject.eu/model#hasRepresentation")) {
            reps.add(fetchRepresentation(session, repUri.substring(11)));
        }
        ie.representations(reps);

        /* fetch the lifecycle state */
        final String state =
                getFirstLiteralString(entityModel, parent,
                        "http://scapeproject.eu/model#hasLifeCycleState");
        final String details =
                getFirstLiteralString(entityModel, parent,
                        "http://scapeproject.eu/model#hasLifeCycleStateDetails");
        ie.lifecycleState(new LifecycleState(details, LifecycleState.State
                .valueOf(state)));

        return ie.build();
    }

    public BitStream fetchBitStream(final Session session, final String bsUri)
            throws RepositoryException {
        final BitStream.Builder bs = new BitStream.Builder();
        bs.identifier(new Identifier(bsUri
                .substring(bsUri.lastIndexOf('/') + 1)));
        bs.technical(fetchMetadata(session, bsUri + "/TECHNICAL"));
        return bs.build();
    }

    public File fetchFile(final Session session, final String fileUri)
            throws RepositoryException {
        final File.Builder f = new File.Builder();
        final FedoraObject fileObject =
                this.objectService.getObject(session, fileUri);
        final Model repModel =
                SerializationUtils.unifyDatasetModel(fileObject
                        .getPropertiesDataset());
        final Resource parent =
                repModel.createResource("info:fedora" + fileObject.getPath());

        /* fetch and add the properties and metadata from the repo */
        f.technical(fetchMetadata(session, fileUri + "/TECHNICAL"));
        String fileId = fileUri.substring(fileUri.lastIndexOf('/') + 1);
        f.identifier(new Identifier(fileId));
        f.filename(getFirstLiteralString(repModel, parent,
                "http://scapeproject.eu/model#hasFileName"));
        f.mimetype(getFirstLiteralString(repModel, parent,
                "http://scapeproject.eu/model#hasMimeType"));
        f.uri(URI.create(fedoraUrl + "/" + ENTITY_FOLDER + "/" + fileId));

        /* discover all the Bistreams and add them to the file */
        final List<BitStream> streams = new ArrayList<>();
        for (String bsUri : getLiteralStrings(repModel, parent,
                "http://scapeproject.eu/model#hasBitStream")) {
            streams.add(fetchBitStream(session, bsUri.substring(11)));
        }
        f.bitStreams(streams);

        return f.build();
    }

    public Object fetchMetadata(final Session session, final String path)
            throws RepositoryException {
        try {
            final Datastream mdDs =
                    this.datastreamService.getDatastream(session, path);
            return this.marshaller.deserialize(mdDs.getContent());
        } catch (JAXBException e) {
            throw new RepositoryException(e);
        }
    }

    public Representation fetchRepresentation(final Session session,
            final String repUri) throws RepositoryException {
        final Representation.Builder rep = new Representation.Builder();
        final FedoraObject repObject =
                this.objectService.getObject(session, repUri);
        final Model repModel =
                SerializationUtils.unifyDatasetModel(repObject
                        .getPropertiesDataset());
        final Resource parent =
                repModel.createResource("info:fedora" + repObject.getPath());

        /* find the title and id */
        rep.identifier(new Identifier(repUri
                .substring(repUri.lastIndexOf('/') + 1)));
        rep.title(getFirstLiteralString(repModel, parent,
                "http://scapeproject.eu/model#hasTitle"));

        /* find and add the metadata */
        rep.technical(fetchMetadata(session, repObject.getPath() + "/TECHNICAL"));
        rep.source(fetchMetadata(session, repObject.getPath() + "/SOURCE"));
        rep.provenance(fetchMetadata(session, repObject.getPath() +
                "/PROVENANCE"));
        rep.rights(fetchMetadata(session, repObject.getPath() + "/RIGHTS"));

        /* add the individual files */
        final List<File> files = new ArrayList<>();
        for (String fileUri : getLiteralStrings(repModel, parent,
                "http://scapeproject.eu/model#hasFile")) {
            files.add(fetchFile(session, fileUri.substring(11)));
        }

        rep.files(files);
        return rep.build();
    }

    public String addEntity(final Session session, final InputStream src) throws RepositoryException{
        return addEntity(session, src,null);
    }

    public String addEntity(final Session session, final InputStream src,
            String entityId) throws RepositoryException {
        try {

            /* read the post body into an IntellectualEntity object */
            final IntellectualEntity ie =
                    this.marshaller.deserialize(IntellectualEntity.class, src);
            final StringBuilder sparql = new StringBuilder();

            if (entityId == null ){
                if (ie.getIdentifier() != null) {
                    entityId = ie.getIdentifier().getValue();
                }else {
                    entityId = UUID.randomUUID().toString();
                }

            }
            /* create the entity top level object in fcrepo */
            final String entityPath = ENTITY_FOLDER + "/" + entityId;

            if (this.objectService.exists(session, "/" + entityPath)) {
                /* return a 409: Conflict result */
                throw new ItemExistsException("Entity '" + entityId +
                        "' already exists");
            }

            final FedoraObject entityObject =
                    objectService.createObject(session, entityPath);

            /* add the metadata datastream for descriptive metadata */
            sparql.append(addMetadata(session, ie.getDescriptive(), entityPath +
                    "/DESCRIPTIVE"));

            /* add all the representations */
            sparql.append(addRepresentations(session, ie.getRepresentations(),
                    entityPath));

            /* update the intellectual entity's properties */
            sparql.append("INSERT {<info:fedora/" + entityObject.getPath() +
                    "> <http://scapeproject.eu/model#hasLifeCycleState> \"" +
                    LifecycleState.State.INGESTED + "\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" +
                    entityObject.getPath() +
                    "> <http://scapeproject.eu/model#hasLifeCycleStateDetails> \"successfully ingested at " +
                    new Date().getTime() + "\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" + entityObject.getPath() +
                    "> <http://scapeproject.eu/model#hasType> \"intellectualentity\"} WHERE {};");

            /* update the object and it's child's using sparql */
            entityObject.updatePropertiesDataset(sparql.toString());

            /* save the changes made to the objects */
            session.save();

            return entityId;

        } catch (JAXBException e) {
            LOG.error(e.getLocalizedMessage(), e);
            throw new RepositoryException(e);
        }
    }

    private String addBitStreams(final Session session,
            final List<BitStream> bitStreams, final String filePath)
            throws RepositoryException {

        final StringBuilder sparql = new StringBuilder();

        for (BitStream bs : bitStreams) {
            final String bsId =
                    (bs.getIdentifier() != null) ? bs.getIdentifier()
                            .getValue() : UUID.randomUUID().toString();
            final String bsPath = filePath + "/" + bsId;
            final FedoraObject bsObject =
                    this.objectService.createObject(session, bsPath);
            sparql.append(addMetadata(session, bs.getTechnical(), bsPath +
                    "/TECHNICAL"));

            sparql.append("INSERT {<info:fedora/" + bsObject.getPath() +
                    "> <http://scapeproject.eu/model#hasType> \"bitstream\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" + bsObject.getPath() +
                    "> <http://scapeproject.eu/model#hasBitstreamType> \"" +
                    bs.getType() + "\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" +
                    filePath +
                    "> <http://scapeproject.eu/model#hasBitStream> <info:fedora/" +
                    bsObject.getPath() + ">} WHERE {};");
        }

        return sparql.toString();
    }

    private String addFiles(final Session session, final List<File> files,
            final String repPath) throws RepositoryException {

        final StringBuilder sparql = new StringBuilder();
        for (File f : files) {

            final String fileId =
                    (f.getIdentifier() != null) ? f.getIdentifier().getValue()
                            : UUID.randomUUID().toString();
            final String filePath = repPath + "/" + fileId;

            /* get a handle on the binary data associated with this file */
            LOG.info("fetching file from " + f.getUri().toASCIIString());
            try (final InputStream src = f.getUri().toURL().openStream()) {

                /* create a datastream in fedora for this file */
                final FedoraObject fileObject =
                        this.objectService.createObject(session, filePath);

                /* add the binary data referenced in the file as a datastream */
                final Node fileDs =
                        this.datastreamService.createDatastreamNode(session,
                                filePath + "/DATA", "text/xml", src);

                /* add the metadata */
                sparql.append(addMetadata(session, f.getTechnical(), filePath +
                        "/TECHNICAL"));

                /* add all bitstreams as child objects */
                sparql.append(addBitStreams(session, f.getBitStreams(), repPath));

                sparql.append("INSERT {<info:fedora/" + fileObject.getPath() +
                        "> <http://scapeproject.eu/model#hasType> \"file\"} WHERE {};");
                sparql.append("INSERT {<info:fedora/" + fileObject.getPath() +
                        "> <http://scapeproject.eu/model#hasFileName> \"" +
                        f.getFilename() + "\"} WHERE {};");
                sparql.append("INSERT {<info:fedora/" + fileObject.getPath() +
                        "> <http://scapeproject.eu/model#hasMimeType> \"" +
                        f.getMimetype() + "\"} WHERE {};");
                sparql.append("INSERT {<info:fedora/" + fileObject.getPath() +
                        "> <http://scapeproject.eu/model#hasIngestSource> \"" +
                        f.getUri() + "\"} WHERE {};");
                sparql.append("INSERT {<info:fedora/" +
                        repPath +
                        "> <http://scapeproject.eu/model#hasFile> <info:fedora/" +
                        fileObject.getPath() + ">} WHERE {};");

            } catch (IOException e) {
                throw new RepositoryException(e);
            } catch (InvalidChecksumException e) {
                throw new RepositoryException(e);
            }
        }

        return sparql.toString();
    }

    private String addMetadata(final Session session, final Object descriptive,
            final String path) throws RepositoryException {
        final StringBuilder sparql = new StringBuilder();
        try {

            /* use piped streams to copy the data to the repo */
            final PipedInputStream dcSrc = new PipedInputStream();
            final PipedOutputStream dcSink = new PipedOutputStream();
            dcSink.connect(dcSrc);
            new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        ConnectorService.this.marshaller.getJaxbMarshaller()
                                .marshal(descriptive, dcSink);
                        dcSink.flush();
                        dcSink.close();
                    } catch (JAXBException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    } catch (IOException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    }
                }
            }).start();

            final Node desc =
                    datastreamService.createDatastreamNode(session, path,
                            "text/xml", dcSrc);

            /* get the type of the metadata */
            String type = "unknown";
            String schema = "";

            if (descriptive.getClass() == ElementContainer.class) {
                type = "dublin-core";
                schema = "http://purl.org/dc/elements/1.1/";
            } else if (descriptive.getClass() == GbsType.class) {
                type = "gbs";
                schema = "http://books.google.com/gbs";
            } else if (descriptive.getClass() == Fits.class) {
                type = "fits";
                schema = "http://hul.harvard.edu/ois/xml/ns/fits/fits_output";
            } else if (descriptive.getClass() == AudioType.class) {
                type = "audiomd";
                schema = "http://www.loc.gov/audioMD/";
            } else if (descriptive.getClass() == RecordType.class) {
                type = "marc21";
                schema = "http://www.loc.gov/MARC21/slim";
            } else if (descriptive.getClass() == Mix.class) {
                type = "mix";
                schema = "http://www.loc.gov/mix/v20";
            } else if (descriptive.getClass() == VideoType.class) {
                type = "videomd";
                schema = "http://www.loc.gov/videoMD/";
            } else if (descriptive.getClass() == PremisComplexType.class) {
                type = "premis-provenance";
                schema = "info:lc/xmlns/premis-v2";
            } else if (descriptive.getClass() == RightsComplexType.class) {
                type = "premis-rights";
                schema = "info:lc/xmlns/premis-v2";
            } else if (descriptive.getClass() == TextMD.class) {
                type = "textmd";
                schema = "info:lc/xmlns/textmd-v3";
            }

            /* add a sparql query to set the type of this object */
            sparql.append("INSERT {<info:fedora/" + desc.getPath() +
                    "> <http://scapeproject.eu/model#hasType> \"" + type +
                    "\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" + desc.getPath() +
                    "> <http://scapeproject.eu/model#hasSchema> \"" + schema +
                    "\"} WHERE {};");

            return sparql.toString();

        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (InvalidChecksumException e) {
            throw new RepositoryException(e);
        }
    }

    private String
            addRepresentations(final Session session,
                    final List<Representation> representations,
                    final String entityPath) throws RepositoryException {
        final StringBuilder sparql = new StringBuilder();
        for (Representation rep : representations) {

            final String repId =
                    (rep.getIdentifier() != null) ? rep.getIdentifier()
                            .getValue() : UUID.randomUUID().toString();
            final String repPath = entityPath + "/" + repId;
            final FedoraObject repObject =
                    objectService.createObject(session, repPath);

            /* add the metadatasets of the rep as datastreams */
            sparql.append(addMetadata(session, rep.getTechnical(), repPath +
                    "/TECHNICAL"));
            sparql.append(addMetadata(session, rep.getSource(), repPath +
                    "/SOURCE"));
            sparql.append(addMetadata(session, rep.getRights(), repPath +
                    "/RIGHTS"));
            sparql.append(addMetadata(session, rep.getProvenance(), repPath +
                    "/PROVENANCE"));

            /* add all the files */
            sparql.append(addFiles(session, rep.getFiles(), repPath));

            /* add a sparql query to set the type of this object */
            sparql.append("INSERT {<info:fedora/" + repObject.getPath() +
                    "> <http://scapeproject.eu/model#hasType> \"representation\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" + repObject.getPath() +
                    "> <http://scapeproject.eu/model#hasTitle> \"" +
                    rep.getTitle() + "\"} WHERE {};");
            sparql.append("INSERT {<info:fedora/" +
                    entityPath +
                    "> <http://scapeproject.eu/model#hasRepresentation> <info:fedora/" +
                    repObject.getPath() + ">} WHERE {};");

        }
        return sparql.toString();
    }

    private String getFirstLiteralString(Model model, Resource subject,
            String propertyName) {

        final Property p = model.createProperty(propertyName);
        final StmtIterator it =
                model.listStatements(subject, p, (RDFNode) null);
        return it.next().getObject().toString();
    }

    private List<String> getLiteralStrings(Model model, Resource subject,
            String propertyName) {
        final List<String> result = new ArrayList<>();
        final Property p = model.createProperty(propertyName);
        final StmtIterator it =
                model.listStatements(subject, p, (RDFNode) null);
        while (it.hasNext()) {
            result.add(it.next().getObject().toString());
        }
        return result;
    }

    public String queueEntityForIngest(final Session session,
            final InputStream src) throws RepositoryException {
        final String fileName = UUID.randomUUID().toString() + ".tmp";
        final java.io.File tmp = new java.io.File(tempDirectory, fileName);
        try {
            if (!tmp.createNewFile()) {
                throw new RepositoryException("temporary file for id'" +
                        fileName + "' does already exist");
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RepositoryException(e);
        }

        /* copy the data to a temp location */
        try (final FileOutputStream sink = new FileOutputStream(tmp)) {
            IOUtils.copy(src, sink);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }

        /* update the ingest queue */
        final FedoraObject queue =
                this.objectService.getObject(session, QUEUE_NODE);
        final String sparql =
                "INSERT {<info:fedora/" + QUEUE_NODE +
                        "> <http://scapeproject.eu/model#hasItem> \"" +
                        tmp.getAbsolutePath() + "\"} WHERE {}";
        queue.updatePropertiesDataset(sparql);
        session.save();
        return fileName.substring(0, fileName.length() - 4);
    }

    public LifecycleState fetchLifeCycleState(Session session, String entityId)
            throws RepositoryException {
        /* check if the entity exists */
        if (this.objectService.exists(session, "/" + ENTITY_FOLDER + "/" +
                entityId)) {
            /* fetch the state form the entity itself */
            final FedoraObject entityObject =
                    this.objectService.getObject(session, "/" + ENTITY_FOLDER +
                            "/" + entityId);
            final Model entityModel =
                    SerializationUtils.unifyDatasetModel(entityObject
                            .getPropertiesDataset());
            final Resource parent =
                    entityModel.createResource("info:fedora" +
                            entityObject.getPath());
            final String state =
                    this.getFirstLiteralString(entityModel, parent,
                            "http://scapeproject.eu/model#hasLifeCycleState");
            final String details =
                    this.getFirstLiteralString(entityModel, parent,
                            "http://scapeproject.eu/model#hasLifeCycleStateDetails");
            return new LifecycleState(details, LifecycleState.State
                    .valueOf(state));
        } else {
            /* check the async queue for the id */
            final FedoraObject queueObject =
                    this.objectService.getObject(session, QUEUE_NODE);
            final Model queueModel =
                    SerializationUtils.unifyDatasetModel(queueObject
                            .getPropertiesDataset());
            final Resource parent =
                    queueModel.createResource("info:fedora" +
                            queueObject.getPath());
            final List<String> asyncIds =
                    this.getLiteralStrings(queueModel, parent,
                            "http://scapeproject.eu/model#hasItem");
            if (asyncIds.contains(tempDirectory + "/" + entityId + ".tmp")) {
                return new LifecycleState("", State.INGESTING);
            }
        }
        throw new ItemNotFoundException("Unable to find lifecycle for '" +
                entityId + "'");

    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void ingestFromQueue() throws RepositoryException {
        final Session session = sessionFactory.getSession();
        if (!this.objectService.exists(session, QUEUE_NODE)) {
            return;
        }
        for (String item : getItemsFromQueue(session)) {
            try {
                addEntity(session, new FileInputStream(item), item.substring(
                        item.lastIndexOf('/') + 1, item.length() - 4));
                deleteFromQueue(session, item);
            } catch (IOException e) {
                LOG.error("Error while processing async ingest", e);
            }
        }
        session.logout();
    }

    private void deleteFromQueue(final Session session, final String item)
            throws RepositoryException {
        final FedoraObject queueObject =
                this.objectService.getObject(session, QUEUE_NODE);
        final String sparql =
                "DELETE {<info:fedora/" + QUEUE_NODE +
                        "> <http://scapeproject.eu/model#hasItem> \"" + item +
                        "\"} WHERE {}";
        queueObject.updatePropertiesDataset(sparql);
        session.save();
    }

    private List<String> getItemsFromQueue(final Session session)
            throws RepositoryException {
        final FedoraObject queueObject =
                this.objectService.getObject(session, QUEUE_NODE);
        final Model queueModel =
                SerializationUtils.unifyDatasetModel(queueObject
                        .getPropertiesDataset());
        final Resource parent =
                queueModel
                        .createResource("info:fedora" + queueObject.getPath());
        return this.getLiteralStrings(queueModel, parent,
                "http://scapeproject.eu/model#hasItem");
    }

}
