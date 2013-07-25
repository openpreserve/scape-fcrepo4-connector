/**
 *
 */

package eu.scapeproject.fcrepo.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import info.lc.xmlns.textmd_v3.TextMD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.xml.bind.JAXBException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.purl.dc.elements._1.ElementContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import eu.scapeproject.model.BitStream;
import eu.scapeproject.model.File;
import eu.scapeproject.model.IntellectualEntity;
import eu.scapeproject.model.IntellectualEntityCollection;
import eu.scapeproject.model.LifecycleState;
import eu.scapeproject.model.LifecycleState.State;
import eu.scapeproject.model.Representation;
import eu.scapeproject.model.TestUtil;
import eu.scapeproject.util.ScapeMarshaller;

/**
 * @author frank asseg
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"/integration-tests/test-container.xml"})
public class IntellectualEntitiesIT {

    private static final String SCAPE_URL = "http://localhost:8080/rest/scape";

    private static final String FEDORA_URL = "http://localhost:8080/rest/";

    private final DefaultHttpClient client = new DefaultHttpClient();

    private ScapeMarshaller marshaller;

    private static final Logger LOG = LoggerFactory
            .getLogger(IntellectualEntitiesIT.class);

    @Before
    public void setup() throws Exception {
        this.marshaller = ScapeMarshaller.newInstance();
    }

    @Test
    public void testIngestIntellectualEntityAndCheckinFedora() throws Exception {
        IntellectualEntity ie = TestUtil.createTestEntity("entity-1");
        this.postEntity(ie);

        HttpGet get =
                new HttpGet(FEDORA_URL + "/objects/scape/entities/entity-1");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        assertTrue(EntityUtils.toString(resp.getEntity()).length() > 0);
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveIntellectualEntity() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-2");
        this.postEntity(ie);

        HttpGet get = new HttpGet(SCAPE_URL + "/entity/entity-2");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        IntellectualEntity fetched =
                this.marshaller.deserialize(IntellectualEntity.class, resp
                        .getEntity().getContent());
        assertEquals(ie.getIdentifier(), fetched.getIdentifier());
        assertEquals(LifecycleState.State.INGESTED, fetched.getLifecycleState()
                .getState());
        assertEquals(ie.getRepresentations().size(), fetched
                .getRepresentations().size());
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveRepresentation() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-3");
        this.postEntity(ie);

        Representation rep = ie.getRepresentations().get(0);
        HttpGet get =
                new HttpGet(SCAPE_URL + "/representation/entity-3/" +
                        rep.getIdentifier().getValue());
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        Representation fetched =
                this.marshaller.deserialize(Representation.class, resp
                        .getEntity().getContent());
        assertEquals(rep.getIdentifier().getValue(), fetched.getIdentifier()
                .getValue());
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveFile() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-4");
        this.postEntity(ie);

        Representation rep = ie.getRepresentations().get(0);
        File f = ie.getRepresentations().get(0).getFiles().get(0);
        HttpGet get =
                new HttpGet(SCAPE_URL + "/file/entity-4/" +
                        rep.getIdentifier().getValue() + "/" +
                        f.getIdentifier().getValue());
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        File fetched =
                this.marshaller.deserialize(File.class, resp.getEntity()
                        .getContent());
        assertEquals(f.getIdentifier().getValue(), fetched.getIdentifier()
                .getValue());
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveBitstream() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-5");
        this.postEntity(ie);

        Representation rep = ie.getRepresentations().get(0);
        File f = rep.getFiles().get(0);
        BitStream bs = f.getBitStreams().get(0);
        HttpGet get =
                new HttpGet(SCAPE_URL + "/bitstream/entity-5/" +
                        rep.getIdentifier().getValue() + "/" +
                        f.getIdentifier().getValue() + "/" +
                        bs.getIdentifier().getValue());
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        BitStream fetched =
                (BitStream) this.marshaller.deserialize(resp.getEntity()
                        .getContent());
        assertEquals(bs.getIdentifier().getValue(), fetched.getIdentifier()
                .getValue());
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveLifeCycle() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-6");
        this.postEntity(ie);

        /* check the lifecycle state */
        HttpGet get = new HttpGet(SCAPE_URL + "/lifecycle/entity-6");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        LifecycleState state =
                (LifecycleState) this.marshaller.deserialize(resp.getEntity()
                        .getContent());
        assertEquals(LifecycleState.State.INGESTED, state.getState());
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveMetadata() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-7");
        this.postEntity(ie);

        /* check the desc metadata of the entity */
        HttpGet get = new HttpGet(SCAPE_URL + "/metadata/entity-7/DESCRIPTIVE");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());

        Object md = this.marshaller.deserialize(resp.getEntity().getContent());
        assertEquals(md.getClass(), ElementContainer.class);
        get.releaseConnection();

        /* check the tech metadata of the rep */
        get =
                new HttpGet(SCAPE_URL +
                        "/metadata/entity-7/" +
                        ie.getRepresentations().get(0).getIdentifier()
                                .getValue() + "/TECHNICAL");
        resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());

        md = this.marshaller.deserialize(resp.getEntity().getContent());
        assertEquals(md.getClass(), TextMD.class);
        get.releaseConnection();
    }

    @Test
    public void testIngestAndRetrieveIntellectualEntityCollection()
            throws Exception {
        IntellectualEntity ie1 = TestUtil.createTestEntity("entity-8");
        this.postEntity(ie1);

        IntellectualEntity ie2 = TestUtil.createTestEntity("entity-9");
        this.postEntity(ie2);

        /* check the desc metadata of the entity */
        HttpPost post = new HttpPost(SCAPE_URL + "/entity-list");
        String uriList =
                FEDORA_URL + "/scape/entity/entity-8\n" +
                        FEDORA_URL + "scape/entity/entity-9";
        post.setEntity(new StringEntity(uriList, ContentType.parse("text/uri-list")));
        HttpResponse resp = this.client.execute(post);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        IntellectualEntityCollection coll = this.marshaller.deserialize(IntellectualEntityCollection.class, resp.getEntity().getContent());
        post.releaseConnection();
        assertEquals(2,coll.getEntities().size());
    }

    @Test
    public void testIngestAsyncAndRetrieveLifeCycle() throws Exception {
        IntellectualEntity ie =
                TestUtil.createTestEntityWithMultipleRepresentations("entity-10");
        HttpPost post = new HttpPost(SCAPE_URL + "/entity-async");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        this.marshaller.serialize(ie, sink);
        post.setEntity(new InputStreamEntity(new ByteArrayInputStream(sink
                .toByteArray()), sink.size()));
        HttpResponse resp = this.client.execute(post);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        String id = EntityUtils.toString(resp.getEntity());
        assertTrue(id.length() > 0);

        /* check the lifecycle state and wait for the entity to be ingested */
        LifecycleState state;
        long start = System.currentTimeMillis();
        do {
            HttpGet get = new HttpGet(SCAPE_URL + "/lifecycle/" + id);
            resp = this.client.execute(get);
            assertEquals(200, resp.getStatusLine().getStatusCode());
            state =
                    (LifecycleState) this.marshaller.deserialize(resp
                            .getEntity().getContent());
            get.releaseConnection();
        } while (!state.getState().equals(State.INGESTED) &&
                (System.currentTimeMillis() - start) < 15000);
        assertEquals(State.INGESTED, state.getState());
    }

    @Test
    public void testIngestAndSearchEntity() throws Exception {
        IntellectualEntity ie1 = TestUtil.createTestEntity("entity-11");
        this.postEntity(ie1);

        IntellectualEntity ie2 = TestUtil.createTestEntity("entity-12");
        this.postEntity(ie2);

        /* search via SRU */
        HttpGet get = new HttpGet(SCAPE_URL + "/sru/entities?version=1&operation=searchRetrieve&query=*");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        String xml = EntityUtils.toString(resp.getEntity(),"UTF-8");
        System.out.println(xml);
        assertTrue(0 < xml.length());
        assertTrue(xml.indexOf("ID=\"entity-12\" OBJID=\"entity-12\"") > 0);
        assertTrue(xml.indexOf("ID=\"entity-11\" OBJID=\"entity-11\"") > 0);
        get.releaseConnection();

    }

    @Test
    public void testIngestAndSearchRepresentation() throws Exception {
        IntellectualEntity ie1 = TestUtil.createTestEntity("entity-13");
        this.postEntity(ie1);

        IntellectualEntity ie2 = TestUtil.createTestEntity("entity-14");
        this.postEntity(ie2);

        /* search via SRU */
        HttpGet get = new HttpGet(SCAPE_URL + "/sru/representations?version=1&operation=searchRetrieve&query=*");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        String xml = EntityUtils.toString(resp.getEntity(),"UTF-8");
        System.out.println(xml);
        assertTrue(0 < xml.length());
        assertTrue(xml.indexOf("<scape:identifier type=\"String\"><scape:value>representation-1</scape:value></scape:identifier>") > 0);
        get.releaseConnection();

    }

    @Test
    public void testIngestAndSearchFile() throws Exception {
        IntellectualEntity ie1 = TestUtil.createTestEntity("entity-15");
        this.postEntity(ie1);

        IntellectualEntity ie2 = TestUtil.createTestEntity("entity-16");
        this.postEntity(ie2);

        /* search via SRU */
        HttpGet get = new HttpGet(SCAPE_URL + "/sru/files?version=1&operation=searchRetrieve&query=*");
        HttpResponse resp = this.client.execute(get);
        assertEquals(200, resp.getStatusLine().getStatusCode());
        String xml = EntityUtils.toString(resp.getEntity(),"UTF-8");
        System.out.println(xml);
        assertTrue(0 < xml.length());
        assertTrue(xml.indexOf("<scape:identifier type=\"String\"><scape:value>file-1</scape:value></scape:identifier>") > 0);
        get.releaseConnection();

    }

    private void postEntity(IntellectualEntity ie) throws IOException {
        HttpPost post = new HttpPost(SCAPE_URL + "/entity");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            this.marshaller.serialize(ie, sink);
        } catch (JAXBException e) {
            throw new IOException(e);
        }
        post.setEntity(new InputStreamEntity(new ByteArrayInputStream(sink
                .toByteArray()), sink.size()));
        HttpResponse resp = this.client.execute(post);
        assertEquals(201, resp.getStatusLine().getStatusCode());
        String id = EntityUtils.toString(resp.getEntity());
        assertTrue(id.length() > 0);
        post.releaseConnection();
    }
}
