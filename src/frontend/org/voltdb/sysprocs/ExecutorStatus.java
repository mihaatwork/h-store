package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.voltdb.DependencySet;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

import edu.brown.hstore.PartitionExecutor;

/** 
 * Get a status snapshot of the PartitionExecutors in the cluster
 * @author pavlo
 */
@ProcInfo(partitionParam=0)
public class ExecutorStatus extends VoltSystemProcedure {

    public static final ColumnInfo nodeResultsColumns[] = {
        new ColumnInfo("SITE",          VoltType.INTEGER),
        new ColumnInfo("PARTITION",     VoltType.INTEGER),
        new ColumnInfo("TXNS_TOTAL",    VoltType.INTEGER),
        new ColumnInfo("TXNS_QUEUED",   VoltType.INTEGER),
        new ColumnInfo("TXNS_BLOCKED",  VoltType.INTEGER),
        new ColumnInfo("TXNS_WAITING",  VoltType.INTEGER),
        new ColumnInfo("CURRENT_TXN",   VoltType.BIGINT),
        new ColumnInfo("CURRENT_DTXN",  VoltType.BIGINT),
        new ColumnInfo("LAST_EXECUTED", VoltType.BIGINT),
        new ColumnInfo("LAST_COMMITTED", VoltType.BIGINT),
        new ColumnInfo("CREATED", VoltType.TIMESTAMP),
    };
    
    @Override
    public void initImpl() {
        executor.registerPlanFragment(SysProcFragmentId.PF_execStatus, this);
    }

    @Override
    public DependencySet executePlanFragment(long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        assert(fragmentId == SysProcFragmentId.PF_execStatus);
        
        // Hit up all of the PartitionExecutors at this HStore and figure out what
        // they got going on
        VoltTable vt = new VoltTable(nodeResultsColumns);
        for (Integer p : hstore_site.getLocalPartitionIdArray()) {
            PartitionExecutor es = hstore_site.getPartitionExecutor(p.intValue());
            PartitionExecutor.Debug dbg = es.getDebugContext();
            Queue<?> es_queue = dbg.getWorkQueue();
                
            Long currentTxnId = dbg.getCurrentTxnId();
            Long currentDtxnId = dbg.getCurrentDtxnId();
            Long lastCommitted = dbg.getLastCommittedTxnId();
            Long lastExecuted = dbg.getLastExecutedTxnId(); 
            
            vt.addRow(es.getSiteId(),
                      es.getPartitionId(),
                      es_queue.size(),
                      dbg.getWorkQueueSize(),
                      dbg.getBlockedQueueSize(),
                      dbg.getWaitingQueueSize(),
                      (currentTxnId != null  ? currentTxnId.longValue()  : VoltType.NULL_BIGINT),
                      (currentDtxnId != null ? currentDtxnId.longValue() : VoltType.NULL_BIGINT),
                      (lastExecuted != null  ? lastExecuted.longValue()  : VoltType.NULL_BIGINT),
                      (lastCommitted != null ? lastCommitted.longValue() : VoltType.NULL_BIGINT),
                      new TimestampType());
        } // FOR
        
        DependencySet result = new DependencySet(SysProcFragmentId.PF_execStatus, vt);
        return (result);
    }

    public VoltTable[] run(int partitionId) {
        return executeLocal(SysProcFragmentId.PF_execStatus, new ParameterSet());
    }
}
