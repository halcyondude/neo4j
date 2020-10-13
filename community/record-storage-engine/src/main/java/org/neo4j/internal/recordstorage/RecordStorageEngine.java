/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.recordstorage;

import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.factory.primitive.LongObjectMaps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.counts.CountsAccessor;
import org.neo4j.exceptions.KernelException;
import org.neo4j.exceptions.UnderlyingStorageException;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.counts.CountsBuilder;
import org.neo4j.internal.counts.GBPTreeCountsStore;
import org.neo4j.internal.counts.GBPTreeGenericCountsStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStoreFactory;
import org.neo4j.internal.diagnostics.DiagnosticsLogger;
import org.neo4j.internal.diagnostics.DiagnosticsManager;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.id.IdController;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.IdType;
import org.neo4j.internal.kernel.api.exceptions.TransactionApplyKernelException;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.recordstorage.NeoStoresDiagnostics.NeoStoreIdUsage;
import org.neo4j.internal.recordstorage.NeoStoresDiagnostics.NeoStoreRecords;
import org.neo4j.internal.recordstorage.NeoStoresDiagnostics.NeoStoreVersions;
import org.neo4j.internal.schema.IndexConfigCompleter;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.impl.store.CountsComputer;
import org.neo4j.kernel.impl.store.IdUpdateListener;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.RelationshipGroupStore;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.StoreType;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.MetaDataRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.lock.LockService;
import org.neo4j.lock.LockTracer;
import org.neo4j.lock.ResourceLocker;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.Health;
import org.neo4j.storageengine.api.CommandCreationContext;
import org.neo4j.storageengine.api.CommandsToApply;
import org.neo4j.storageengine.api.ConstraintRuleAccessor;
import org.neo4j.storageengine.api.EntityTokenUpdateListener;
import org.neo4j.storageengine.api.IndexUpdateListener;
import org.neo4j.storageengine.api.MetadataProvider;
import org.neo4j.storageengine.api.RelationshipDirection;
import org.neo4j.storageengine.api.StorageCommand;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.StoreFileMetadata;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.TransactionApplicationMode;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TransactionCountingStateVisitor;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.token.TokenHolders;
import org.neo4j.util.Preconditions;
import org.neo4j.util.VisibleForTesting;
import org.neo4j.util.concurrent.WorkSync;

import static org.neo4j.function.ThrowingAction.executeAll;
import static org.neo4j.kernel.impl.store.record.Record.isNull;
import static org.neo4j.lock.LockService.NO_LOCK_SERVICE;
import static org.neo4j.storageengine.api.TransactionApplicationMode.RECOVERY;
import static org.neo4j.storageengine.api.TransactionApplicationMode.REVERSE_RECOVERY;

public class RecordStorageEngine implements StorageEngine, Lifecycle
{
    private static final String STORAGE_ENGINE_START_TAG = "storageEngineStart";
    private static final String SCHEMA_CACHE_START_TAG = "schemaCacheStart";
    private static final String TOKENS_INIT_TAG = "tokensInitialisation";

    private final NeoStores neoStores;
    private final DatabaseLayout databaseLayout;
    private final TokenHolders tokenHolders;
    private final Health databaseHealth;
    private final SchemaCache schemaCache;
    private final IntegrityValidator integrityValidator;
    private final CacheAccessBackDoor cacheAccess;
    private final SchemaState schemaState;
    private final SchemaRuleAccess schemaRuleAccess;
    private final ConstraintRuleAccessor constraintSemantics;
    private final LockService lockService;
    private final boolean consistencyCheckApply;
    private final boolean relaxedLockingForDenseNodes;
    private WorkSync<EntityTokenUpdateListener,TokenUpdateWork> labelScanStoreSync;
    private WorkSync<EntityTokenUpdateListener,TokenUpdateWork> relationshipTypeScanStoreSync;
    private WorkSync<IndexUpdateListener,IndexUpdatesWork> indexUpdatesSync;
    private final IdController idController;
    private final PageCacheTracer cacheTracer;
    private final MemoryTracker otherMemoryTracker;
    private final CommandLockVerification.Factory commandLockVerificationFactory;
    private final LockVerificationMonitor.Factory lockVerificationFactory;
    private final GBPTreeCountsStore countsStore;
    private final RelationshipGroupDegreesStore groupDegreesStore;
    private final int denseNodeThreshold;
    private final Map<IdType,WorkSync<IdGenerator,IdGeneratorUpdateWork>> idGeneratorWorkSyncs = new EnumMap<>( IdType.class );
    private final Map<TransactionApplicationMode,TransactionApplierFactoryChain> applierChains = new EnumMap<>( TransactionApplicationMode.class );

    // installed later
    private IndexUpdateListener indexUpdateListener;
    private EntityTokenUpdateListener nodeLabelUpdateListener;
    private EntityTokenUpdateListener relationshipTypeUpdateListener;

    public RecordStorageEngine( DatabaseLayout databaseLayout,
            Config config,
            PageCache pageCache,
            FileSystemAbstraction fs,
            LogProvider logProvider,
            TokenHolders tokenHolders,
            SchemaState schemaState,
            ConstraintRuleAccessor constraintSemantics,
            IndexConfigCompleter indexConfigCompleter,
            LockService lockService,
            Health databaseHealth,
            IdGeneratorFactory idGeneratorFactory,
            IdController idController,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector,
            PageCacheTracer cacheTracer,
            boolean createStoreIfNotExists,
            MemoryTracker otherMemoryTracker,
            CommandLockVerification.Factory commandLockVerificationFactory,
            LockVerificationMonitor.Factory lockVerificationFactory
    )
    {
        this.databaseLayout = databaseLayout;
        this.tokenHolders = tokenHolders;
        this.schemaState = schemaState;
        this.lockService = lockService;
        this.databaseHealth = databaseHealth;
        this.constraintSemantics = constraintSemantics;
        this.idController = idController;
        this.cacheTracer = cacheTracer;
        this.otherMemoryTracker = otherMemoryTracker;
        this.commandLockVerificationFactory = commandLockVerificationFactory;
        this.lockVerificationFactory = lockVerificationFactory;

        StoreFactory factory = new StoreFactory( databaseLayout, config, idGeneratorFactory, pageCache, fs, logProvider, cacheTracer );
        neoStores = factory.openAllNeoStores( createStoreIfNotExists );
        for ( IdType idType : IdType.values() )
        {
            idGeneratorWorkSyncs.put( idType, new WorkSync<>( idGeneratorFactory.get( idType ) ) );
        }

        try
        {
            schemaRuleAccess = SchemaRuleAccess.getSchemaRuleAccess( neoStores.getSchemaStore(), tokenHolders );
            schemaCache = new SchemaCache( constraintSemantics, indexConfigCompleter );

            integrityValidator = new IntegrityValidator( neoStores );
            cacheAccess = new BridgingCacheAccess( schemaCache, schemaState, tokenHolders );

            denseNodeThreshold = config.get( GraphDatabaseSettings.dense_node_threshold );

            countsStore = openCountsStore( pageCache, fs, databaseLayout, config, logProvider, recoveryCleanupWorkCollector, cacheTracer );

            groupDegreesStore = openDegreesStore( pageCache, fs, databaseLayout, config, recoveryCleanupWorkCollector, cacheTracer );
            relaxedLockingForDenseNodes = RelationshipGroupDegreesStoreFactory.featureEnabled( config, databaseLayout, fs );

            consistencyCheckApply = config.get( GraphDatabaseInternalSettings.consistency_check_on_apply );
        }
        catch ( Throwable failure )
        {
            neoStores.close();
            throw failure;
        }
    }

    private void buildApplierChains()
    {
        for ( TransactionApplicationMode mode : TransactionApplicationMode.values() )
        {
            applierChains.put( mode, buildApplierFacadeChain( mode ) );
        }
    }

    private TransactionApplierFactoryChain buildApplierFacadeChain( TransactionApplicationMode mode )
    {
        Supplier<IdUpdateListener> listenerSupplier = mode == REVERSE_RECOVERY ? () -> IdUpdateListener.IGNORE :
                                                      () -> new EnqueuingIdUpdateListener( idGeneratorWorkSyncs, cacheTracer );
        List<TransactionApplierFactory> appliers = new ArrayList<>();
        // Graph store application. The order of the decorated store appliers is irrelevant
        if ( consistencyCheckApply && mode.needsAuxiliaryStores() )
        {
            appliers.add( new ConsistencyCheckingApplierFactory( neoStores ) );
        }
        appliers.add( new NeoStoreTransactionApplierFactory( mode, neoStores, cacheAccess, lockService( mode ) ) );
        if ( mode.needsHighIdTracking() )
        {
            appliers.add( new HighIdTransactionApplierFactory( neoStores ) );
        }
        if ( mode.needsCacheInvalidationOnUpdates() )
        {
            appliers.add( new CacheInvalidationTransactionApplierFactory( neoStores, cacheAccess ) );
        }
        if ( mode.needsAuxiliaryStores() )
        {
            // Counts store application
            appliers.add( new CountsStoreTransactionApplierFactory( countsStore, groupDegreesStore ) );

            // Schema index application
            appliers.add( new IndexTransactionApplierFactory( indexUpdateListener ) );
        }
        return new TransactionApplierFactoryChain( listenerSupplier, appliers.toArray( new TransactionApplierFactory[0] ) );
    }

    private GBPTreeCountsStore openCountsStore( PageCache pageCache, FileSystemAbstraction fs, DatabaseLayout layout, Config config, LogProvider logProvider,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector, PageCacheTracer pageCacheTracer )
    {
        try
        {
            boolean readOnly = config.get( GraphDatabaseSettings.read_only );
            return new GBPTreeCountsStore( pageCache, layout.countStore(), fs, recoveryCleanupWorkCollector, new CountsBuilder()
            {
                private final Log log = logProvider.getLog( MetaDataStore.class );

                @Override
                public void initialize( CountsAccessor.Updater updater, PageCursorTracer cursorTracer, MemoryTracker memoryTracker )
                {
                    log.warn( "Missing counts store, rebuilding it." );
                    new CountsComputer( neoStores, pageCache, pageCacheTracer, layout, memoryTracker, log ).initialize( updater, cursorTracer, memoryTracker );
                    log.warn( "Counts store rebuild completed." );
                }

                @Override
                public long lastCommittedTxId()
                {
                    return neoStores.getMetaDataStore().getLastCommittedTransactionId();
                }
            }, readOnly, pageCacheTracer, GBPTreeGenericCountsStore.NO_MONITOR );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    private RelationshipGroupDegreesStore openDegreesStore( PageCache pageCache, FileSystemAbstraction fs, DatabaseLayout layout, Config config,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector, PageCacheTracer pageCacheTracer )
    {
        try
        {
            return RelationshipGroupDegreesStoreFactory.create( config, pageCache, layout, fs, recoveryCleanupWorkCollector,
                    () -> neoStores.getMetaDataStore().getLastCommittedTransactionId(), pageCacheTracer, GBPTreeGenericCountsStore.NO_MONITOR );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    @Override
    public RecordStorageReader newReader()
    {
        return new RecordStorageReader( tokenHolders, neoStores, countsStore, groupDegreesStore, schemaCache );
    }

    @Override
    public RecordStorageCommandCreationContext newCommandCreationContext( PageCursorTracer cursorTracer, MemoryTracker memoryTracker )
    {
        return new RecordStorageCommandCreationContext( neoStores, denseNodeThreshold, relaxedLockingForDenseNodes, cursorTracer, memoryTracker );
    }

    @Override
    public void addIndexUpdateListener( IndexUpdateListener listener )
    {
        Preconditions.checkState( this.indexUpdateListener == null,
                "Only supports a single listener. Tried to add " + listener + ", but " + this.indexUpdateListener + " has already been added" );
        this.indexUpdateListener = listener;
        this.indexUpdatesSync = new WorkSync<>( listener );
        this.integrityValidator.setIndexValidator( listener );
    }

    @Override
    public void addNodeLabelUpdateListener( EntityTokenUpdateListener listener )
    {
        Preconditions.checkState( this.nodeLabelUpdateListener == null,
                "Only supports a single listener. Tried to add " + listener + ", but " + this.nodeLabelUpdateListener + " has already been added" );
        this.nodeLabelUpdateListener = listener;
        this.labelScanStoreSync = new WorkSync<>( listener );
    }

    @Override
    public void addRelationshipTypeUpdateListener( EntityTokenUpdateListener listener )
    {
        Preconditions.checkState( this.relationshipTypeUpdateListener == null,
                "Only supports a single listener. Tried to add " + listener + ", but " + this.relationshipTypeUpdateListener + " has already been added" );
        this.relationshipTypeUpdateListener = listener;
        this.relationshipTypeScanStoreSync = new WorkSync<>( listener );
    }

    /**
     * @throws TransactionFailureException if command generation fails or some prerequisite of some command didn't validate,
     * for example if trying to delete a node that still has relationships.
     * @throws CreateConstraintFailureException if this transaction was set to create a constraint and that failed.
     * @throws ConstraintValidationException if this transaction was set to create a constraint and some data violates that constraint.
     */
    @SuppressWarnings( "resource" )
    @Override
    public void createCommands(
            Collection<StorageCommand> commands,
            ReadableTransactionState txState,
            StorageReader storageReader,
            CommandCreationContext commandCreationContext,
            ResourceLocker locks,
            LockTracer lockTracer,
            long lastTransactionIdWhenStarted,
            TxStateVisitor.Decorator additionalTxStateVisitor,
            PageCursorTracer cursorTracer,
            MemoryTracker transactionMemoryTracker )
            throws KernelException
    {
        if ( txState != null )
        {
            KernelVersion version = neoStores.getMetaDataStore().kernelVersion();
            Preconditions
                    .checkState( version.isAtLeast( KernelVersion.V4_2 ), "Can not write older version than %s. Requested %s", KernelVersion.V4_2, version );

            // We can make this cast here because we expected that the storageReader passed in here comes from
            // this storage engine itself, anything else is considered a bug. And we do know the inner workings
            // of the storage statements that we create.
            RecordStorageCommandCreationContext creationContext = (RecordStorageCommandCreationContext) commandCreationContext;
            LogCommandSerialization serialization = RecordStorageCommandReaderFactory.INSTANCE.get( version );
            TransactionRecordState recordState =
                    creationContext.createTransactionRecordState( integrityValidator, lastTransactionIdWhenStarted, locks, lockTracer,
                            serialization, lockVerificationFactory.create( locks, txState, neoStores ) );

            // Visit transaction state and populate these record state objects
            TxStateVisitor txStateVisitor = new TransactionToRecordStateVisitor( recordState, schemaState,
                    schemaRuleAccess, constraintSemantics, cursorTracer );
            CountsRecordState countsRecordState = new CountsRecordState( serialization );
            txStateVisitor = additionalTxStateVisitor.apply( txStateVisitor );
            txStateVisitor = new TransactionCountingStateVisitor( txStateVisitor, storageReader, txState, countsRecordState, cursorTracer );
            try ( TxStateVisitor visitor = txStateVisitor )
            {
                txState.accept( visitor );
            }

            // Convert record state into commands
            recordState.extractCommands( commands, transactionMemoryTracker );
            countsRecordState.extractCommands( commands, transactionMemoryTracker );

            //Verify sufficient locks
            CommandLockVerification commandLockVerification = commandLockVerificationFactory.create( locks, txState, neoStores );
            commandLockVerification.verifySufficientlyLocked( commands );
        }
    }

    @Override
    public List<StorageCommand> createUpgradeCommands( KernelVersion versionToUpgradeTo )
    {
        MetaDataStore metaDataStore = neoStores.getMetaDataStore();
        KernelVersion currentVersion = metaDataStore.kernelVersion();
        Preconditions.checkState( currentVersion.isAtLeast( KernelVersion.V4_2 ),
                "Upgrade transaction was introduced in %s and must be done from at least %s. Tried upgrading from %s to %s",
                KernelVersion.V4_3_D3, KernelVersion.V4_2, currentVersion, versionToUpgradeTo );
        Preconditions.checkState( versionToUpgradeTo.isGreaterThan( currentVersion ), "Can not downgrade from %s to %s", currentVersion, versionToUpgradeTo );

        int id = MetaDataStore.Position.KERNEL_VERSION.id();

        MetaDataRecord before = metaDataStore.newRecord();
        before.setId( id );
        before.initialize( true, currentVersion.version() );

        MetaDataRecord after = metaDataStore.newRecord();
        after.setId( id );
        after.initialize( true, versionToUpgradeTo.version() );

        //This command will be the first one in the "new" version, indicating the switch and writing it to the MetaDataStore
        LogCommandSerialization serialization = RecordStorageCommandReaderFactory.INSTANCE.get( versionToUpgradeTo );
        return List.of( new Command.MetaDataCommand( serialization, before, after ) );
    }

    @Override
    public void apply( CommandsToApply batch, TransactionApplicationMode mode ) throws Exception
    {
        TransactionApplierFactoryChain batchApplier = applierChain( mode );
        CommandsToApply initialBatch = batch;
        try ( BatchContext context = new BatchContext( indexUpdateListener, labelScanStoreSync, relationshipTypeScanStoreSync, indexUpdatesSync,
                neoStores.getNodeStore(), neoStores.getPropertyStore(), this, schemaCache, initialBatch.cursorTracer(), otherMemoryTracker,
                batchApplier.getIdUpdateListenerSupplier().get() ) )
        {
            while ( batch != null )
            {
                try ( TransactionApplier txApplier = batchApplier.startTx( batch, context ) )
                {
                    batch.accept( txApplier );
                }
                batch = batch.next();
            }
        }
        catch ( Throwable cause )
        {
            TransactionApplyKernelException kernelException = new TransactionApplyKernelException(
                    cause, "Failed to apply transaction: %s", batch == null ? initialBatch : batch );
            databaseHealth.panic( kernelException );
            throw kernelException;
        }
    }

    /**
     * Provides a {@link TransactionApplierFactoryChain} that is to be used for all transactions
     * in a batch. Each transaction is handled by a {@link TransactionApplierFacade} which wraps the
     * individual {@link TransactionApplier}s returned by the wrapped {@link TransactionApplierFactory}s.
     */
    protected TransactionApplierFactoryChain applierChain( TransactionApplicationMode mode )
    {
        return applierChains.get( mode );
    }

    private LockService lockService( TransactionApplicationMode mode )
    {
        return mode == RECOVERY || mode == REVERSE_RECOVERY ? NO_LOCK_SERVICE : lockService;
    }

    @Override
    public void init()
    {
        buildApplierChains();
    }

    @Override
    public void start() throws Exception
    {
        try ( var cursor = cacheTracer.createPageCursorTracer( STORAGE_ENGINE_START_TAG ) )
        {
            neoStores.start( cursor );
            countsStore.start( cursor, otherMemoryTracker );
            groupDegreesStore.start( cursor, otherMemoryTracker );
            idController.start();
        }
    }

    @VisibleForTesting
    public void loadSchemaCache()
    {
        try ( var cursor = cacheTracer.createPageCursorTracer( SCHEMA_CACHE_START_TAG ) )
        {
            schemaCache.load( schemaRuleAccess.getAll( cursor ) );
        }
    }

    @Override
    public void stop() throws Exception
    {
        executeAll( idController::stop );
    }

    @Override
    public void shutdown() throws Exception
    {
        executeAll( countsStore::close, groupDegreesStore::close, neoStores::close );
    }

    @Override
    public void flushAndForce( IOLimiter limiter, PageCursorTracer cursorTracer ) throws IOException
    {
        countsStore.checkpoint( limiter, cursorTracer );
        groupDegreesStore.checkpoint( limiter, cursorTracer );
        neoStores.flush( limiter, cursorTracer );
    }

    @Override
    public void dumpDiagnostics( Log errorLog, DiagnosticsLogger diagnosticsLog )
    {
        DiagnosticsManager.dump( new NeoStoreIdUsage( neoStores ), errorLog, diagnosticsLog );
        DiagnosticsManager.dump( new NeoStoreRecords( neoStores ), errorLog, diagnosticsLog );
        DiagnosticsManager.dump( new NeoStoreVersions( neoStores ), errorLog, diagnosticsLog );
    }

    @Override
    public void forceClose()
    {
        try
        {
            shutdown();
        }
        catch ( Throwable e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public void listStorageFiles( Collection<StoreFileMetadata> atomic, Collection<StoreFileMetadata> replayable )
    {
        atomic.add( new StoreFileMetadata( databaseLayout.countStore(), RecordFormat.NO_RECORD_SIZE ) );
        if ( relaxedLockingForDenseNodes )
        {
            atomic.add( new StoreFileMetadata( databaseLayout.relationshipGroupDegreesStore(), RecordFormat.NO_RECORD_SIZE ) );
        }
        for ( StoreType type : StoreType.values() )
        {
            final RecordStore<AbstractBaseRecord> recordStore = neoStores.getRecordStore( type );
            StoreFileMetadata metadata = new StoreFileMetadata( recordStore.getStorageFile(), recordStore.getRecordSize() );
            replayable.add( metadata );
        }
    }

    /**
     * @return the underlying {@link NeoStores} which should <strong>ONLY</strong> be accessed by tests
     * until all tests are properly converted to not rely on access to {@link NeoStores}. Currently there
     * are important tests which asserts details about the neo stores that are very important to test,
     * but to convert all those tests might be a bigger piece of work.
     */
    @VisibleForTesting
    public NeoStores testAccessNeoStores()
    {
        return neoStores;
    }

    @VisibleForTesting
    public SchemaRuleAccess testAccessSchemaRules()
    {
        return schemaRuleAccess;
    }

    @Override
    public StoreId getStoreId()
    {
        return neoStores.getMetaDataStore().getStoreId();
    }

    @Override
    public Lifecycle schemaAndTokensLifecycle()
    {
        return new LifecycleAdapter()
        {
            @Override
            public void init()
            {
                try ( var cursorTracer = cacheTracer.createPageCursorTracer( TOKENS_INIT_TAG ) )
                {
                    tokenHolders.setInitialTokens( StoreTokens.allTokens( neoStores ), cursorTracer );
                }
                loadSchemaCache();
            }
        };
    }

    @Override
    public CountsAccessor countsAccessor()
    {
        return countsStore;
    }

    @Override
    public MetadataProvider metadataProvider()
    {
        return neoStores.getMetaDataStore();
    }

    public void unsafeConvertAllDenseChainsToExternalDegrees( boolean collectStats )
    {
        Histo degreeHisto = new Histo( "Degrees", 5, 10, 50 );
        MutableLongObjectMap<MutableInt> numTypesPerNode = collectStats ? LongObjectMaps.mutable.empty() : null;
        RelationshipGroupStore groupStore = neoStores.getRelationshipGroupStore();
        RelationshipGroupRecord group = groupStore.newRecord();
        RelationshipStore relationshipStore = neoStores.getRelationshipStore();
        RelationshipRecord relationship = relationshipStore.newRecord();
        long highId = groupStore.getHighId();
        ProgressListener progress = ProgressMonitorFactory.textual( System.out ).singlePart( "Convert dense chains to external degrees", highId );
        int numGroupsConverted = 0;
        int numGroupsVisited = 0;
        try ( DegreesReaderWriterManager degreesManager = new DegreesReaderWriterManager( groupDegreesStore ) )
        {
            for ( long id = groupStore.getNumberOfReservedLowIds(); id < highId; id++ )
            {
                groupStore.getRecord( id, group, RecordLoad.ALWAYS, PageCursorTracer.NULL );
                if ( group.inUse() )
                {
                    numGroupsVisited++;
                    if ( collectStats )
                    {
                        numTypesPerNode.getIfAbsentPut( group.getOwningNode(), MutableInt::new ).increment();
                    }
                    boolean changed = false;
                    changed |= convertDirection( group, group::hasExternalDegreesOut, () -> group.setHasExternalDegreesOut( true ), group.getFirstOut(),
                            RelationshipDirection.OUTGOING, relationshipStore, relationship, degreesManager, degreeHisto );
                    changed |= convertDirection( group, group::hasExternalDegreesIn, () -> group.setHasExternalDegreesIn( true ), group.getFirstIn(),
                            RelationshipDirection.INCOMING, relationshipStore, relationship, degreesManager, degreeHisto );
                    changed |= convertDirection( group, group::hasExternalDegreesLoop, () -> group.setHasExternalDegreesLoop( true ), group.getFirstLoop(),
                            RelationshipDirection.LOOP, relationshipStore, relationship, degreesManager, degreeHisto );
                    if ( changed )
                    {
                        groupStore.updateRecord( group, IdUpdateListener.IGNORE, PageCursorTracer.NULL );
                        numGroupsConverted++;
                    }
                }
                progress.add( 1 );
            }
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        finally
        {
            progress.done();
        }

        if ( collectStats )
        {
            System.out.println( "Num groups visited: " + numGroupsVisited );
            System.out.println( "Num groups converted: " + numGroupsConverted );
            Histo numTypesPerNodeHisto = new Histo( "Num types per dense node", 1, 2, 5, 10, 20, 50 );
            numTypesPerNode.values().forEach( numTypes -> numTypesPerNodeHisto.add( numTypes.longValue() ) );
            System.out.println( degreeHisto );
            System.out.println( numTypesPerNodeHisto );
        }
    }

    private boolean convertDirection( RelationshipGroupRecord group, BooleanSupplier hasExternalDegrees, Runnable setExternalDegrees, long firstRelationship,
            RelationshipDirection direction, RelationshipStore relationshipStore, RelationshipRecord relationship,
            DegreesReaderWriterManager degreesManager, Histo degreeHisto ) throws IOException
    {
        boolean converted = false;
        long degree = 0;
        if ( !hasExternalDegrees.getAsBoolean() )
        {
            converted = true;
            if ( !isNull( firstRelationship )  )
            {
                relationshipStore.getRecord( firstRelationship, relationship, RecordLoad.NORMAL, PageCursorTracer.NULL );
                degree = relationship.getPrevRel( group.getOwningNode() );
                degreesManager.increment( group.getId(), direction, degree );
                setExternalDegrees.run();
            }
        }
        else if ( !isNull( firstRelationship ) )
        {
            degree = degreesManager.read( group.getId(), direction );
        }

        degreeHisto.add( degree );
        return converted;
    }

    private static class Histo
    {
        private final String name;
        private final long[] counts;
        private final long[] thresholds;

        Histo( String name, long... thresholds )
        {
            this.name = name;
            this.counts = new long[thresholds.length + 1];
            this.thresholds = thresholds;
        }

        void add( long value )
        {
            for ( int i = 0; i < thresholds.length; i++ )
            {
                if ( value < thresholds[i] )
                {
                    counts[i]++;
                    return;
                }
            }
            counts[counts.length - 1]++;
        }

        @Override
        public String toString()
        {
            long total = LongStream.of( counts ).sum();
            StringBuilder builder = new StringBuilder( name + "[total:" + total + "]:" );
            long collective = 0;
            for ( int i = 0; i < thresholds.length; i++ )
            {
                collective += counts[i];
                builder.append( String.format( "%n  < %d: %d : %.2f", thresholds[i], counts[i], 100D * collective / total ) );
            }
            builder.append( String.format( "%n  > %d", counts[counts.length - 1] ) );
            return builder.toString();
        }
    }

    private static class DegreesReaderWriterManager implements AutoCloseable
    {
        private final RelationshipGroupDegreesStore store;
        private RelationshipGroupDegreesStore.Updater updater;

        DegreesReaderWriterManager( RelationshipGroupDegreesStore store )
        {
            this.store = store;
        }

        private RelationshipGroupDegreesStore.Updater updater() throws IOException
        {
            if ( updater == null )
            {
                updater = store.unsafeApply();
            }
            return updater;
        }

        void increment( long groupId, RelationshipDirection direction, long delta ) throws IOException
        {
            updater().increment( groupId, direction, delta );
        }

        long read( long groupId, RelationshipDirection direction )
        {
            ensureUpdaterClosed();
            return store.degree( groupId, direction, PageCursorTracer.NULL );
        }

        private void ensureUpdaterClosed()
        {
            if ( updater != null )
            {
                updater.close();
                updater = null;
            }
        }

        @Override
        public void close()
        {
            ensureUpdaterClosed();
        }
    }
}
