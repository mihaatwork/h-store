package edu.brown.hstore;

import java.util.concurrent.Semaphore;

import org.junit.Test;
import org.voltdb.catalog.Site;

import com.google.protobuf.RpcCallback;

import edu.brown.BaseTestCase;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.Hstoreservice.TransactionInitResponse;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.ProjectType;
import edu.brown.utils.ThreadUtil;

public class TestTransactionQueueManager extends BaseTestCase {

    private static final int NUM_PARTITONS = 4;
    
    HStoreSite hstore_site;
    HStoreConf hstore_conf;
    TransactionQueueManager queue;
    TransactionQueueManager.Debug dbg;
    
    class MockCallback implements RpcCallback<TransactionInitResponse> {
        Semaphore lock = new Semaphore(0);
        boolean invoked = false;
        
        @Override
        public void run(TransactionInitResponse parameter) {
            assertFalse(invoked);
            lock.release();
            invoked = true;
        }
    }
    
    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TPCC);
        addPartitions(NUM_PARTITONS);
        
        hstore_conf = HStoreConf.singleton();
        hstore_conf.site.txn_incoming_delay = 10;
        
        Site catalog_site = CollectionUtil.first(catalogContext.sites);
        assertNotNull(catalog_site);
        this.hstore_site = new MockHStoreSite(catalog_site, HStoreConf.singleton());
        this.queue = new TransactionQueueManager(hstore_site);
        this.dbg = this.queue.getDebugContext();
    }
    
    /**
     * Insert the txn into our queue and then call check
     * This should immediately release our transaction and invoke the inner_callback
     * @throws InterruptedException 
     */
    @Test
    public void testSingleTransaction() throws InterruptedException {
        long txn_id = 1000;
        PartitionSet partitions = catalogContext.getAllPartitionIds();
        
        MockCallback inner_callback = new MockCallback();
        
        // Insert the txn into our queue and then call check
        // This should immediately release our transaction and invoke the inner_callback
        assertTrue(this.queue.lockQueueInsert(txn_id, partitions, inner_callback, false));
        
        int tries = 10;
        while (dbg.isLockQueuesEmpty() == false && tries-- > 0) {
            queue.checkLockQueues();
            ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay);
        }
        assert(inner_callback.lock.availablePermits() > 0);
        // Block on the MockCallback's lock until our thread above is able to release everybody.
        // inner_callback.lock.acquire();
    }
    
    /**
     * Add two, check that only one comes out
     * Mark first as done, second comes out
     * @throws InterruptedException 
     */
    @Test
    public void testTwoTransactions() throws InterruptedException {
        final long txn_id0 = 1000;
        final long txn_id1 = 2000;
        PartitionSet partitions0 = new PartitionSet(catalogContext.getAllPartitionIds());
        PartitionSet partitions1 = new PartitionSet(catalogContext.getAllPartitionIds());
        
        final MockCallback inner_callback0 = new MockCallback();
        final MockCallback inner_callback1 = new MockCallback();
        
        // insert the higher ID first but make sure it comes out second
        assertFalse(queue.checkLockQueues());
        assertTrue(this.queue.lockQueueInsert(txn_id1, partitions1, inner_callback1, false));
        assertTrue(this.queue.lockQueueInsert(txn_id0, partitions0, inner_callback0, false));
        
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        assertTrue(queue.checkLockQueues());
        
        assertTrue("callback0", inner_callback0.lock.tryAcquire());
        assertFalse("callback1", inner_callback1.lock.tryAcquire());
        assertFalse(dbg.isLockQueuesEmpty());
        for (int partition = 0; partition < NUM_PARTITONS; ++partition) {
            queue.lockQueueFinished(txn_id0, Status.OK, partition);
        }
        assertFalse(dbg.isLockQueuesEmpty());
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        
        assertTrue(queue.checkLockQueues());
        assertTrue("callback1", inner_callback1.lock.tryAcquire());
        assertTrue(dbg.isLockQueuesEmpty());
        for (int partition = 0; partition < NUM_PARTITONS; ++partition) {
            queue.lockQueueFinished(txn_id1, Status.OK, partition);
        }
        
        assertFalse(queue.checkLockQueues());
        assertTrue(dbg.isLockQueuesEmpty());
    }
    
    /**
     * Add two disjoint partitions and third that touches all partitions
     * Two come out right away and get marked as done
     * Third doesn't come out until everyone else is done
     * @throws InterruptedException 
     */
    @Test
    public void testDisjointTransactions() throws InterruptedException {
        long txn_id = 1000;
        final long txn_id0 = txn_id++;
        final long txn_id1 = txn_id++;
        final long txn_id2 = txn_id++;
        PartitionSet partitions0 = new PartitionSet(0, 2);
        PartitionSet partitions1 = new PartitionSet(1, 3);
        PartitionSet partitions2 = catalogContext.getAllPartitionIds();
        
        final MockCallback inner_callback0 = new MockCallback();
        final MockCallback inner_callback1 = new MockCallback();
        final MockCallback inner_callback2 = new MockCallback();
        
        this.queue.lockQueueInsert(txn_id0, partitions0, inner_callback0, false);
        this.queue.lockQueueInsert(txn_id1, partitions1, inner_callback1, false);
        this.queue.lockQueueInsert(txn_id2, partitions2, inner_callback2, false);
        
        // Both of the first two disjoint txns should be released on the same call to checkQueues()
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        assertTrue(queue.checkLockQueues());
        assertTrue("callback0", inner_callback0.lock.tryAcquire());
        assertTrue("callback1", inner_callback1.lock.tryAcquire());
        assertFalse("callback2", inner_callback2.lock.tryAcquire());
        assertFalse(dbg.isLockQueuesEmpty());
        
        // Now release mark the first txn as finished. We should still 
        // not be able to get the third txn's lock
        for (int partition : partitions0) {
            queue.lockQueueFinished(txn_id0, Status.OK, partition);
        }
        assertFalse(queue.toString(), queue.checkLockQueues());
        assertFalse("callback2", inner_callback2.lock.tryAcquire());
        assertFalse(dbg.isLockQueuesEmpty());
        
        // Release the second txn. That should release the third txn
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        for (int partition : partitions1) {
            queue.lockQueueFinished(txn_id1, Status.OK, partition);
        }
        assertTrue(queue.checkLockQueues());
        assertTrue("callback2", inner_callback2.lock.tryAcquire());
        assertTrue(dbg.isLockQueuesEmpty());
    }
    
    /**
     * Add two overlapping partitions, lowest id comes out
     * Mark first as done, second comes out
     * @throws InterruptedException 
     */
    @Test
    public void testOverlappingTransactions() throws InterruptedException {
        final long txn_id0 = 1000;
        final long txn_id1 = 2000;
        PartitionSet partitions0 = new PartitionSet(0, 1, 2);
        PartitionSet partitions1 = new PartitionSet(2, 3);
        
        final MockCallback inner_callback0 = new MockCallback();
        final MockCallback inner_callback1 = new MockCallback();
        
        this.queue.lockQueueInsert(txn_id0, partitions0, inner_callback0, false);
        this.queue.lockQueueInsert(txn_id1, partitions1, inner_callback1, false);
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        
        // We should get the callback for the first txn right away
        assertTrue(queue.checkLockQueues());
        assertTrue("callback0", inner_callback0.lock.tryAcquire());
        
        // And then checkLockQueues should always return false
        assertFalse(queue.checkLockQueues());
        assertFalse(dbg.isLockQueuesEmpty());
        
        // Now if we mark the txn as finished, we should be able to acquire the
        // locks for the second txn. We actually need to call checkLockQueues()
        // twice because we only process the finished txns after the first one
        ThreadUtil.sleep(hstore_conf.site.txn_incoming_delay*2);
        for (int partition = 0; partition < NUM_PARTITONS; ++partition) {
            queue.lockQueueFinished(txn_id0, Status.OK, partition);
        }
        assertTrue(queue.checkLockQueues());
        assertTrue("callback1", inner_callback1.lock.tryAcquire());
        
        for (int partition = 0; partition < NUM_PARTITONS; ++partition) {
            queue.lockQueueFinished(txn_id1, Status.OK, partition);
        }
        assertFalse(queue.checkLockQueues());
        assertTrue(dbg.isLockQueuesEmpty());
    }
}
