//! Network Optimization Engine - Packet Batching & Compression
//!
//! Optimizes multiplayer network performance by:
//! - Batching small packets into larger aggregated payloads
//! - Compressing packet data using ZSTD
//! - Deduplicating redundant position/movement updates
//! - Prioritizing critical packets (combat, chat, inventory)
//!
//! # Packet Processing Pipeline
//!
//! ```text
//! Incoming          ┌──────────┐    ┌──────────┐    ┌──────────┐
//! Packets ─────────>│ Priority │ -> │ Dedup    │ -> │ Batch    │ -> Send
//!                   │ Queue    │    │ Filter   │    │ & Compress│
//!                   └──────────┘    └──────────┘    └──────────┘
//!                   (sort by        (remove          (ZSTD for
//!                    importance)     duplicates)      large batches)
//! ```

use std::collections::HashMap;
use std::time::Instant;

/// Packet priority levels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum PacketPriority {
    /// Combat, damage, death events
    Critical = 0,
    /// Chat messages, commands
    High = 1,
    /// Inventory, block changes
    Normal = 2,
    /// Particles, sound effects, ambient
    Low = 3,
    /// Weather, time, scoreboard
    Background = 4,
}

/// A wrapper for a network packet with metadata.
#[derive(Debug, Clone)]
pub struct PacketEntry {
    /// Serialized packet data
    pub data: Vec<u8>,
    /// Priority level
    pub priority: PacketPriority,
    /// Timestamp when the packet was created
    pub timestamp: Instant,
    /// Packet type identifier for deduplication
    pub packet_type: u16,
    /// Target player ID (0 = broadcast)
    pub target_player: u32,
}

/// Result of packet batch compression.
#[derive(Debug, Clone)]
pub struct BatchResult {
    /// Compressed batch data
    pub compressed_data: Vec<u8>,
    /// Number of packets in the batch
    pub packet_count: usize,
    /// Original total size in bytes
    pub original_size: usize,
    /// Compressed size in bytes
    pub compressed_size: usize,
    /// Compression ratio (0.0 - 1.0)
    pub compression_ratio: f64,
}

/// Network statistics for monitoring.
#[derive(Debug, Default, Clone)]
pub struct NetworkStats {
    pub total_packets_processed: u64,
    pub total_packets_batched: u64,
    pub total_packets_deduplicated: u64,
    pub total_bytes_sent: u64,
    pub total_bytes_saved: u64,
    pub total_batches_sent: u64,
    pub lag_spikes_detected: u32,
    pub average_batch_size: f64,
}

/// High-performance network optimization engine.
pub struct NetworkEngine {
    stats: std::sync::Mutex<NetworkStats>,
    /// Compression threshold in bytes (packets larger than this are compressed)
    compression_threshold: usize,
    /// Maximum packets per batch
    max_batch_size: usize,
    /// Batch interval in milliseconds
    batch_interval_ms: u64,
}

impl NetworkEngine {
    /// Create a new network optimization engine with default settings.
    pub fn new() -> Self {
        Self {
            stats: std::sync::Mutex::new(NetworkStats::default()),
            compression_threshold: 256,
            max_batch_size: 64,
            batch_interval_ms: 50,
        }
    }

    /// Create a new network engine with custom settings.
    pub fn with_settings(
        compression_threshold: usize,
        max_batch_size: usize,
        batch_interval_ms: u64,
    ) -> Self {
        Self {
            stats: std::sync::Mutex::new(NetworkStats::default()),
            compression_threshold,
            max_batch_size,
            batch_interval_ms,
        }
    }

    /// Batch multiple packets into a single compressed payload.
    ///
    /// Format:
    /// ```text
    /// [4 bytes: packet count] [for each packet:
    ///   [4 bytes: data length] [N bytes: packet data]
    /// ] [ZSTD compressed]
    /// ```
    pub fn batch_packets(&self, packets: &[PacketEntry]) -> BatchResult {
        if packets.is_empty() {
            return BatchResult {
                compressed_data: Vec::new(),
                packet_count: 0,
                original_size: 0,
                compressed_size: 0,
                compression_ratio: 1.0,
            };
        }

        let start = Instant::now();

        // Serialize all packets with length prefixes
        let mut serialized = Vec::new();

        // Packet count header
        let count = packets.len() as u32;
        serialized.extend_from_slice(&count.to_le_bytes());

        // Individual packets
        let mut original_size = 0usize;
        for packet in packets {
            let len = packet.data.len() as u32;
            serialized.extend_from_slice(&len.to_le_bytes());
            serialized.extend_from_slice(&packet.data);
            original_size += packet.data.len();
        }

        // Compress the batch if it's large enough
        let compressed_data = if serialized.len() > self.compression_threshold {
            match zstd::encode_all(&serialized[..], 3) {
                Ok(compressed) => compressed,
                Err(_) => serialized.clone(), // Fallback to uncompressed
            }
        } else {
            serialized.clone()
        };

        let compressed_size = compressed_data.len();
        let compression_ratio = if original_size > 0 {
            compressed_size as f64 / original_size as f64
        } else {
            1.0
        };

        // Update statistics
        {
            let mut stats = self.stats.lock().unwrap();
            stats.total_packets_processed += packets.len() as u64;
            stats.total_packets_batched += packets.len().saturating_sub(1) as u64;
            stats.total_bytes_sent += compressed_size as u64;
            stats.total_bytes_saved += original_size.saturating_sub(compressed_size) as u64;
            stats.total_batches_sent += 1;

            // Update average batch size
            let total_batches = stats.total_batches_sent as f64;
            stats.average_batch_size =
                (stats.average_batch_size * (total_batches - 1.0) + packets.len() as f64) / total_batches;
        }

        BatchResult {
            compressed_data,
            packet_count: packets.len(),
            original_size,
            compressed_size,
            compression_ratio,
        }
    }

    /// Compress a single packet.
    pub fn compress_packet(&self, data: &[u8]) -> Vec<u8> {
        if data.len() <= self.compression_threshold {
            return data.to_vec();
        }

        match zstd::encode_all(data, 3) {
            Ok(compressed) => {
                if compressed.len() < data.len() {
                    {
                        let mut stats = self.stats.lock().unwrap();
                        stats.total_bytes_saved += data.len().saturating_sub(compressed.len()) as u64;
                    }
                    compressed
                } else {
                    data.to_vec()
                }
            }
            Err(_) => data.to_vec(),
        }
    }

    /// Decompress a single packet.
    pub fn decompress_packet(&self, data: &[u8]) -> Vec<u8> {
        zstd::decode_all(data).unwrap_or_else(|_| data.to_vec())
    }

    /// Deduplicate packets by removing redundant position/movement updates.
    ///
    /// For each player, only the most recent position update is kept.
    /// This is especially useful for reducing bandwidth when players
    /// are moving quickly or when tick rates are high.
    pub fn deduplicate_packets(&self, packets: &mut Vec<PacketEntry>) -> usize {
        // Track the latest position update per player
        let mut latest_position: HashMap<u32, usize> = HashMap::new();

        // Identify duplicates (position updates = packet_type 0x01-0x05)
        for (i, packet) in packets.iter().enumerate() {
            if packet.packet_type >= 0x01 && packet.packet_type <= 0x05 {
                // This is a position/movement update
                if let Some(&prev_idx) = latest_position.get(&packet.target_player) {
                    // Mark previous update for removal
                    // (we keep the newer one)
                }
                latest_position.insert(packet.target_player, i);
            }
        }

        // Remove duplicates (keeping only the latest for each player)
        let original_len = packets.len();

        // Simple deduplication: for consecutive position updates for the same player,
        // keep only the last one
        let mut to_remove = Vec::new();
        let mut player_last_pos: HashMap<u32, usize> = HashMap::new();

        for (i, packet) in packets.iter().enumerate() {
            if packet.packet_type >= 0x01 && packet.packet_type <= 0x05 {
                if let Some(&prev_idx) = player_last_pos.get(&packet.target_player) {
                    to_remove.push(prev_idx);
                }
                player_last_pos.insert(packet.target_player, i);
            }
        }

        // Remove in reverse order to maintain indices
        to_remove.sort_unstable();
        for &idx in to_remove.iter().rev() {
            packets.remove(idx);
        }

        let removed = original_len - packets.len();

        {
            let mut stats = self.stats.lock().unwrap();
            stats.total_packets_deduplicated += removed as u64;
        }

        removed
    }

    /// Detect lag spikes based on packet processing timing.
    pub fn detect_lag_spike(&self, processing_time_ms: u64, threshold_ms: u64) -> bool {
        let is_spike = processing_time_ms > threshold_ms;

        if is_spike {
            let mut stats = self.stats.lock().unwrap();
            stats.lag_spikes_detected += 1;
        }

        is_spike
    }

    /// Get current network statistics.
    pub fn stats(&self) -> NetworkStats {
        self.stats.lock().unwrap().clone()
    }

    /// Set the compression threshold in bytes.
    pub fn set_compression_threshold(&mut self, threshold: usize) {
        self.compression_threshold = threshold;
    }

    /// Set the maximum batch size.
    pub fn set_max_batch_size(&mut self, size: usize) {
        self.max_batch_size = size;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_batch_packets() {
        let engine = NetworkEngine::new();

        let packets: Vec<PacketEntry> = (0..10).map(|i| PacketEntry {
            data: vec![i as u8; 100],
            priority: PacketPriority::Normal,
            timestamp: Instant::now(),
            packet_type: i as u16,
            target_player: 1,
        }).collect();

        let result = engine.batch_packets(&packets);

        assert_eq!(result.packet_count, 10);
        assert!(result.compression_ratio < 1.0);
    }

    #[test]
    fn test_deduplicate_packets() {
        let engine = NetworkEngine::new();

        let mut packets: Vec<PacketEntry> = (0..5).map(|i| PacketEntry {
            data: vec![1, 2, 3],
            priority: PacketPriority::Normal,
            timestamp: Instant::now(),
            packet_type: 0x01, // Position update
            target_player: 1,
        }).collect();

        // Add a non-position packet
        packets.push(PacketEntry {
            data: vec![4, 5, 6],
            priority: PacketPriority::High,
            timestamp: Instant::now(),
            packet_type: 0x10, // Not a position update
            target_player: 1,
        });

        let removed = engine.deduplicate_packets(&mut packets);

        assert_eq!(removed, 4); // Should remove 4 out of 5 position updates
        assert_eq!(packets.len(), 2); // 1 position + 1 non-position
    }

    #[test]
    fn test_compress_decompress_packet() {
        let engine = NetworkEngine::new();

        let data = vec![0u8; 1000];
        let compressed = engine.compress_packet(&data);
        let decompressed = engine.decompress_packet(&compressed);

        assert_eq!(data, decompressed);
    }
}
