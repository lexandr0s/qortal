package org.qortal.controller.repository;

import org.qortal.controller.Controller;

import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.DaemonThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PruneManager {

    private static PruneManager instance;

    private boolean pruningEnabled = Settings.getInstance().isPruningEnabled();
    private int pruneBlockLimit = Settings.getInstance().getPruneBlockLimit();

    private ExecutorService executorService;

    private PruneManager() {

    }

    public static synchronized PruneManager getInstance() {
        if (instance == null)
            instance = new PruneManager();

        return instance;
    }

    public void start() {
        this.executorService = Executors.newCachedThreadPool(new DaemonThreadFactory());

        if (Settings.getInstance().isPruningEnabled() &&
            !Settings.getInstance().isArchiveEnabled()) {
            // Top-only-sync
            this.startTopOnlySyncMode();
        }
        else if (Settings.getInstance().isArchiveEnabled()) {
            // Full node with block archive
            this.startFullNodeWithBlockArchive();
        }
        else {
            // Full node with full SQL support
            this.startFullSQLNode();
        }
    }

    /**
     * Top-only-sync
     * In this mode, we delete (prune) all blocks except
     * a small number of recent ones. There is no need for
     * trimming or archiving, because all relevant blocks
     * are deleted.
     */
    private void startTopOnlySyncMode() {
        this.startPruning();
    }

    /**
     * Full node with block archive
     * In this mode we archive trimmed blocks, and then
     * prune archived blocks to keep the database small
     */
    private void startFullNodeWithBlockArchive() {
        this.startTrimming();
        this.startArchiving();
        this.startPruning();
    }

    /**
     * Full node with full SQL support
     * In this mode we trim the database but don't prune
     * or archive any data, because we want to maintain
     * full SQL support of old blocks. This mode will not
     * be actively maintained but can be used by those who
     * need to perform SQL analysis on older blocks.
     */
    private void startFullSQLNode() {
        this.startTrimming();
    }


    private void startPruning() {
        this.executorService.execute(new AtStatesPruner());
        this.executorService.execute(new BlockPruner());
    }

    private void startTrimming() {
        this.executorService.execute(new AtStatesTrimmer());
        this.executorService.execute(new OnlineAccountsSignaturesTrimmer());
    }

    private void startArchiving() {
        this.executorService.execute(new BlockArchiver());
    }

    public void stop() {
        this.executorService.shutdownNow();

        try {
            this.executorService.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // We tried...
        }
    }

    public boolean isBlockPruned(int height, Repository repository) throws DataException {
        if (!this.pruningEnabled) {
            return false;
        }

        BlockData chainTip = Controller.getInstance().getChainTip();
        if (chainTip == null) {
            throw new DataException("Unable to determine chain tip when checking if a block is pruned");
        }

        final int ourLatestHeight = chainTip.getHeight();
        final int latestUnprunedHeight = ourLatestHeight - this.pruneBlockLimit;

        return (height < latestUnprunedHeight);
    }

}