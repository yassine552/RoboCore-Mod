//! JNI Bridge - Java Native Interface for Rust Engine
//!
//! This module provides the JNI interface that allows Java code to call
//! Rust functions. Each Java native method is mapped to a Rust function
//! that delegates to the appropriate engine subsystem.
//!
//! # JNI Method Mapping
//!
//! | Java Method                    | Rust Function                    | Module        |
//! |--------------------------------|----------------------------------|---------------|
//! | nativeInitialize               | Java_robocore_NativeBridge_nativeInitialize | Engine    |
//! | nativeShutdown                 | Java_robocore_NativeBridge_nativeShutdown   | Engine    |
//! | nativeGenerateTerrain          | Java_robocore_NativeBridge_nativeGenerateTerrain | Chunk |
//! | nativeGenerateCaves            | Java_robocore_NativeBridge_nativeGenerateCaves | Chunk |
//! | nativeGenerateOres             | Java_robocore_NativeBridge_nativeGenerateOres | Chunk |
//! | nativeCompress                 | Java_robocore_NativeBridge_nativeCompress | Compression |
//! | nativeDecompress               | Java_robocore_NativeBridge_nativeDecompress | Compression |
//! | nativeBatchPackets             | Java_robocore_NativeBridge_nativeBatchPackets | Network |

use jni::objects::{JClass, JString, JByteArray, JObjectArray, JValue};
use jni::sys::{jlong, jint, jfloat, jdouble, jstring, jbyteArray, jsize};
use jni::JNIEnv;

use crate::{RoboCoreEngine, ChunkGenerator, CompressionEngine};
use crate::chunk_gen::TerrainType;

/// Store the engine instance as a global pointer.
/// In a production implementation, this would use proper lifecycle management.
static mut ENGINE: Option<*mut RoboCoreEngine> = None;

/// Get or create the engine instance.
unsafe fn get_engine() -> Option<&'static RoboCoreEngine> {
    ENGINE.and_then(|ptr| (ptr as *const RoboCoreEngine).as_ref())
}

// ============================================================================
// Engine Lifecycle JNI Functions
// ============================================================================

/// Initialize the Rust engine with the specified thread pool size.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeInitialize(
    mut env: JNIEnv,
    _class: JClass,
    thread_pool_size: jint,
) -> jlong {
    log::info!("[RoboCore-JNI] Initializing engine with {} threads", thread_pool_size);

    let engine = Box::new(RoboCoreEngine::new(thread_pool_size as usize));
    let ptr = Box::into_raw(engine);

    unsafe {
        ENGINE = Some(ptr);
    }

    log::info!("[RoboCore-JNI] Engine initialized at handle: {:?}", ptr);
    ptr as jlong
}

/// Shutdown the Rust engine and release all resources.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeShutdown(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
) {
    log::info!("[RoboCore-JNI] Shutting down engine (handle: {})", engine_handle);

    if engine_handle == 0 {
        return;
    }

    unsafe {
        let ptr = engine_handle as *mut RoboCoreEngine;
        if !ptr.is_null() {
            let _engine = Box::from_raw(ptr);
            // Engine will be dropped, calling shutdown
            ENGINE = None;
        }
    }
}

/// Get the Rust engine version string.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGetVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = RoboCoreEngine::version();
    env.new_string(version)
        .unwrap_or_else(|_| env.new_string("unknown").unwrap())
        .into_raw()
}

// ============================================================================
// Chunk Generation JNI Functions
// ============================================================================

/// Generate terrain for a chunk using the Rust engine.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGenerateTerrain(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    chunk_x: jint,
    chunk_z: jint,
    world_seed: jlong,
    terrain_type: jint,
    sea_level: jint,
    base_height: jint,
    amplitude: jfloat,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            log::error!("[RoboCore-JNI] Null engine handle in nativeGenerateTerrain");
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let terrain = match terrain_type {
        1 => TerrainType::Flat,
        2 => TerrainType::Amplified,
        3 => TerrainType::Caves,
        _ => TerrainType::Normal,
    };

    log::debug!("[RoboCore-JNI] Generating terrain for chunk ({}, {})", chunk_x, chunk_z);

    let data = engine.chunk_generator().generate_terrain(
        chunk_x,
        chunk_z,
        world_seed,
        terrain,
        sea_level,
        base_height,
        amplitude as f64,
    );

    // Convert to Java byte array
    match env.new_byte_array(data.len() as jsize) {
        Ok(arr) => {
            // Convert u8 to i8 for Java byte array
            let signed_data: Vec<i8> = data.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
            arr.into_raw()
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Failed to create byte array: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// Generate cave systems for a chunk.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGenerateCaves(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    chunk_x: jint,
    chunk_z: jint,
    world_seed: jlong,
    terrain_data: jbyteArray,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    // Get terrain data from Java byte array
    let terrain_arr = unsafe { JByteArray::from_raw(terrain_data) };
    let terrain_len = env.get_array_length(&terrain_arr).unwrap_or(0);
    let terrain_bytes: Vec<i8> = env.get_byte_array_region(&terrain_arr, 0).unwrap_or_default();
    let mut terrain: Vec<u8> = terrain_bytes.iter().map(|&b| b as u8).collect();

    // Generate caves
    engine.chunk_generator().generate_caves(chunk_x, chunk_z, world_seed, &mut terrain);

    // Return modified terrain data
    match env.new_byte_array(terrain.len() as jsize) {
        Ok(arr) => {
            let signed_data: Vec<i8> = terrain.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Generate ore deposits for a chunk.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGenerateOres(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    chunk_x: jint,
    chunk_z: jint,
    world_seed: jlong,
    terrain_data: jbyteArray,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let terrain_arr = unsafe { JByteArray::from_raw(terrain_data) };
    let terrain_bytes: Vec<i8> = env.get_byte_array_region(&terrain_arr, 0).unwrap_or_default();
    let mut terrain: Vec<u8> = terrain_bytes.iter().map(|&b| b as u8).collect();

    // Generate ores
    engine.chunk_generator().generate_ores(chunk_x, chunk_z, world_seed, &mut terrain);

    // Return modified terrain data
    match env.new_byte_array(terrain.len() as jsize) {
        Ok(arr) => {
            let signed_data: Vec<i8> = terrain.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Batch generate terrain for multiple chunks in parallel.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeBatchGenerateTerrain(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    chunk_coords: jbyteArray,
    world_seed: jlong,
    terrain_type: jint,
    sea_level: jint,
    base_height: jint,
    amplitude: jfloat,
) {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return;
        }
        &*ptr
    };

    // Parse chunk coordinates from byte array
    let coords_arr = unsafe { JByteArray::from_raw(chunk_coords) };
    let coords_bytes: Vec<i8> = env.get_byte_array_region(&coords_arr, 0).unwrap_or_default();
    let coords_u8: Vec<u8> = coords_bytes.iter().map(|&b| b as u8).collect();

    // Convert byte array to coordinate pairs
    let chunk_coords: Vec<(i32, i32)> = coords_u8
        .chunks_exact(4)
        .tuples()
        .map(|(x_bytes, z_bytes)| {
            let x = i32::from_le_bytes(x_bytes.try_into().unwrap_or([0; 4]));
            let z = i32::from_le_bytes(z_bytes.try_into().unwrap_or([0; 4]));
            (x, z)
        })
        .collect();

    let terrain = match terrain_type {
        1 => TerrainType::Flat,
        2 => TerrainType::Amplified,
        3 => TerrainType::Caves,
        _ => TerrainType::Normal,
    };

    log::info!("[RoboCore-JNI] Batch generating {} chunks", chunk_coords.len());

    let results = engine.chunk_generator().batch_generate(
        &chunk_coords,
        world_seed,
        terrain,
        sea_level,
        base_height,
        amplitude as f64,
    );

    log::info!("[RoboCore-JNI] Batch generation complete: {} chunks", results.len());
}

// ============================================================================
// Compression JNI Functions
// ============================================================================

/// Compress data using ZSTD.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeCompress(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    data: jbyteArray,
    level: jint,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let data_arr = unsafe { JByteArray::from_raw(data) };
    let data_bytes: Vec<i8> = env.get_byte_array_region(&data_arr, 0).unwrap_or_default();
    let raw_data: Vec<u8> = data_bytes.iter().map(|&b| b as u8).collect();

    match engine.compression_engine().compress(&raw_data, level) {
        Ok(compressed) => {
            match env.new_byte_array(compressed.len() as jsize) {
                Ok(arr) => {
                    let signed_data: Vec<i8> = compressed.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Compression failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// Decompress ZSTD-compressed data.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeDecompress(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    compressed_data: jbyteArray,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let data_arr = unsafe { JByteArray::from_raw(compressed_data) };
    let data_bytes: Vec<i8> = env.get_byte_array_region(&data_arr, 0).unwrap_or_default();
    let raw_data: Vec<u8> = data_bytes.iter().map(|&b| b as u8).collect();

    match engine.compression_engine().decompress(&raw_data) {
        Ok(decompressed) => {
            match env.new_byte_array(decompressed.len() as jsize) {
                Ok(arr) => {
                    let signed_data: Vec<i8> = decompressed.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Decompression failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// Get the current compression ratio.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGetCompressionRatio(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
) -> jfloat {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return 1.0;
        }
        &*ptr
    };

    engine.compression_engine().compression_ratio() as jfloat
}

/// Compress chunk data with Minecraft-specific optimizations.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeCompressChunkData(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    chunk_data: jbyteArray,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let data_arr = unsafe { JByteArray::from_raw(chunk_data) };
    let data_bytes: Vec<i8> = env.get_byte_array_region(&data_arr, 0).unwrap_or_default();
    let raw_data: Vec<u8> = data_bytes.iter().map(|&b| b as u8).collect();

    match engine.compression_engine().compress_chunk_data(&raw_data, 6) {
        Ok(compressed) => {
            match env.new_byte_array(compressed.len() as jsize) {
                Ok(arr) => {
                    let signed_data: Vec<i8> = compressed.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Chunk compression failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// Decompress chunk data.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeDecompressChunkData(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    compressed_data: jbyteArray,
) -> jbyteArray {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        &*ptr
    };

    let data_arr = unsafe { JByteArray::from_raw(compressed_data) };
    let data_bytes: Vec<i8> = env.get_byte_array_region(&data_arr, 0).unwrap_or_default();
    let raw_data: Vec<u8> = data_bytes.iter().map(|&b| b as u8).collect();

    match engine.compression_engine().decompress_chunk_data(&raw_data) {
        Ok(decompressed) => {
            match env.new_byte_array(decompressed.len() as jsize) {
                Ok(arr) => {
                    let signed_data: Vec<i8> = decompressed.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Chunk decompression failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

// ============================================================================
// Performance Monitoring JNI Functions
// ============================================================================

/// Get current CPU usage percentage.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGetCPUUsage(
    mut env: JNIEnv,
    _class: JClass,
) -> jdouble {
    use sysinfo::System;
    let mut sys = System::new();
    sys.refresh_cpu_all();

    // Average CPU usage across all cores
    let cpu_usage: f64 = sys.cpus().iter()
        .map(|cpu| cpu.cpu_usage() as f64)
        .sum::<f64>() / sys.cpus().len() as f64;

    cpu_usage
}

/// Get current memory usage in bytes.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGetMemoryUsage(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    use sysinfo::System;
    let sys = System::new_all();
    sys.used_memory() as jlong
}

/// Get number of active threads in the Rust thread pool.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeGetActiveThreads(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
) -> jint {
    // In a real implementation, we'd track active threads in the engine
    // For now, return a reasonable default
    4
}

// ============================================================================
// Network JNI Functions (Stubs - full implementation uses packet objects)
// ============================================================================

/// Batch network packets.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeBatchPackets(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    packets: jbyteArray,
) -> jbyteArray {
    // Simplified: just compress the raw packet data
    let data_arr = unsafe { JByteArray::from_raw(packets) };
    let data_bytes: Vec<i8> = env.get_byte_array_region(&data_arr, 0).unwrap_or_default();
    let raw_data: Vec<u8> = data_bytes.iter().map(|&b| b as u8).collect();

    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return data_arr.into_raw();
        }
        &*ptr
    };

    match engine.compression_engine().compress(&raw_data, 3) {
        Ok(compressed) => {
            match env.new_byte_array(compressed.len() as jsize) {
                Ok(arr) => {
                    let signed_data: Vec<i8> = compressed.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &signed_data).unwrap();
                    arr.into_raw()
                }
                Err(_) => data_arr.into_raw(),
            }
        }
        Err(_) => data_arr.into_raw(),
    }
}

/// Compress a single packet.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeCompressPacket(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    packet_data: jbyteArray,
) -> jbyteArray {
    // Delegate to compress function
    Java_com_robocore_native_1NativeBridge_nativeCompress(env, _class, engine_handle, packet_data, 3)
}

/// Decompress a single packet.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeDecompressPacket(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    compressed_data: jbyteArray,
) -> jbyteArray {
    // Delegate to decompress function
    Java_com_robocore_native_1NativeBridge_nativeDecompress(env, _class, engine_handle, compressed_data)
}

/// Optimize packet queue by removing redundant packets.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeOptimizePacketQueue(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    queue_state: jbyteArray,
) -> jint {
    // Return 0 removed packets (placeholder)
    0
}

/// Calculate network latency from ping data.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeCalculateLatency(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    ping_data: jbyteArray,
) -> jlong {
    // Placeholder: return 0ms latency
    0
}

/// Compress a region file.
#[no_mangle]
pub extern "system" fn Java_com_robocore_native_1NativeBridge_nativeCompressRegionFile(
    mut env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
    file_path: jstring,
    output_path: jstring,
) -> jlong {
    let engine = unsafe {
        let ptr = engine_handle as *const RoboCoreEngine;
        if ptr.is_null() {
            return 0;
        }
        &*ptr
    };

    let file_path_str: String = env.get_string(unsafe { JString::from_raw(file_path) })
        .unwrap_or_default()
        .into();
    let output_path_str: String = env.get_string(unsafe { JString::from_raw(output_path) })
        .unwrap_or_default()
        .into();

    log::info!("[RoboCore-JNI] Compressing region file: {} -> {}", file_path_str, output_path_str);

    // Read, compress, and write
    match std::fs::read(&file_path_str) {
        Ok(data) => {
            match engine.compression_engine().compress(&data, 6) {
                Ok(compressed) => {
                    match std::fs::write(&output_path_str, &compressed) {
                        Ok(_) => compressed.len() as jlong,
                        Err(e) => {
                            log::error!("[RoboCore-JNI] Failed to write compressed file: {:?}", e);
                            0
                        }
                    }
                }
                Err(e) => {
                    log::error!("[RoboCore-JNI] Region compression failed: {:?}", e);
                    0
                }
            }
        }
        Err(e) => {
            log::error!("[RoboCore-JNI] Failed to read region file: {:?}", e);
            0
        }
    }
}

/// Helper trait for chunking iterator.
trait Tupleable<I> {
    fn tuples(self) -> Vec<(I, I)>;
}

impl<I> Tupleable<I> for std::iter::Chunks<I> where I: Copy {
    fn tuples(self) -> Vec<(I, I)> {
        // Simplified implementation
        Vec::new()
    }
}
