//! RoboCore Performance Engine - Main Rust Library
//!
//! This is the Rust backend for the RoboCore Minecraft mod.
//! It provides high-performance implementations for:
//! - Terrain generation (chunk generation with multi-octave noise)
//! - Data compression (ZSTD-based world save compression)
//! - Network optimization (packet batching and compression)
//!
//! # Architecture
//!
//! ```text
//! ┌──────────────────────────────────────┐
//! │         JNI Interface Layer          │
//! │  (Java calls Rust via JNI)          │
//! ├──────────────────────────────────────┤
//! │         Core Engine                  │
//! │  ┌─────────┐ ┌──────────┐ ┌──────┐ │
//! │  │Chunk Gen│ │Compress  │ │NetOpt│ │
//! │  │Engine   │ │Engine    │ │Engine│ │
//! │  └────┬────┘ └────┬─────┘ └──┬───┘ │
//! │       │           │          │      │
//! │  ┌────┴───────────┴──────────┴───┐ │
//! │  │     Thread Pool (Rayon)       │ │
//! │  └───────────────────────────────┘ │
//! └──────────────────────────────────────┘
//! ```

mod chunk_gen;
mod compression;
mod network;
mod jni_bridge;

pub use chunk_gen::ChunkGenerator;
pub use compression::CompressionEngine;
pub use network::NetworkEngine;

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use rayon::ThreadPool;

/// The main RoboCore engine that manages all subsystems.
pub struct RoboCoreEngine {
    /// Thread pool for parallel processing
    thread_pool: Arc<ThreadPool>,
    /// Chunk generation engine
    chunk_generator: Arc<ChunkGenerator>,
    /// Compression engine
    compression_engine: Arc<CompressionEngine>,
    /// Network optimization engine
    network_engine: Arc<NetworkEngine>,
    /// Whether the engine is running
    running: AtomicBool,
}

impl RoboCoreEngine {
    /// Create a new RoboCore engine with the specified number of threads.
    pub fn new(thread_count: usize) -> Self {
        let thread_pool = Arc::new(
            rayon::ThreadPoolBuilder::new()
                .num_threads(thread_count)
                .thread_name(|idx| format!("robocore-worker-{}", idx))
                .build()
                .expect("Failed to create RoboCore thread pool")
        );

        let chunk_generator = Arc::new(ChunkGenerator::new(thread_pool.clone()));
        let compression_engine = Arc::new(CompressionEngine::new());
        let network_engine = Arc::new(NetworkEngine::new());

        Self {
            thread_pool,
            chunk_generator,
            compression_engine,
            network_engine,
            running: AtomicBool::new(true),
        }
    }

    /// Shutdown the engine and release all resources.
    pub fn shutdown(&self) {
        self.running.store(false, Ordering::SeqCst);
        log::info!("[RoboCore] Engine shutdown complete");
    }

    /// Check if the engine is running.
    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    /// Get a reference to the chunk generator.
    pub fn chunk_generator(&self) -> &Arc<ChunkGenerator> {
        &self.chunk_generator
    }

    /// Get a reference to the compression engine.
    pub fn compression_engine(&self) -> &Arc<CompressionEngine> {
        &self.compression_engine
    }

    /// Get a reference to the network engine.
    pub fn network_engine(&self) -> &Arc<NetworkEngine> {
        &self.network_engine
    }

    /// Get the version string.
    pub fn version() -> &'static str {
        "1.0.0"
    }
}

/// Initialize the RoboCore engine.
#[no_mangle]
pub extern "C" fn robocore_init(thread_count: usize) -> *mut RoboCoreEngine {
    env_logger::init();

    log::info!("========================================");
    log::info!("  RoboCore Rust Engine v{}", RoboCoreEngine::version());
    log::info!("  Thread Pool: {} workers", thread_count);
    log::info!("========================================");

    let engine = Box::new(RoboCoreEngine::new(thread_count));
    Box::into_raw(engine)
}

/// Shutdown the RoboCore engine.
#[no_mangle]
pub unsafe extern "C" fn robocore_shutdown(engine: *mut RoboCoreEngine) {
    if engine.is_null() {
        return;
    }
    let engine = Box::from_raw(engine);
    engine.shutdown();
    log::info!("[RoboCore] Engine destroyed");
}
