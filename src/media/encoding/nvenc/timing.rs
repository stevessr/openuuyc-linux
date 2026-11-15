//! Bounded diagnostics for the driver's synchronous encode path. CPU call
//! durations include any driver waits; conversion submission is not GPU time.
//! The platform wrapper times its own input stages (0: conversion, 1: CUDA
//! input); the shared session times mapping, encoding and the bitstream lock.
use std::time::{Duration, Instant};

pub(crate) struct Frame {
    started: Instant,
    checkpoint: Instant,
    stages: [Duration; 5],
}
impl Frame {
    pub fn new() -> Self {
        let now = Instant::now();
        Self {
            started: now,
            checkpoint: now,
            stages: [Duration::ZERO; 5],
        }
    }
    pub fn stage(&mut self, index: usize) {
        let now = Instant::now();
        self.stages[index] = now.duration_since(self.checkpoint);
        self.checkpoint = now;
    }
}

pub(crate) struct Diagnostics {
    last_report: Option<Instant>,
    frames: u64,
    slow_frames: u64,
    stage_total: [Duration; 5],
    stage_max: [Duration; 5],
    output_total: Duration,
    output_max: Duration,
    worst: Option<(Duration, [Duration; 5], Duration, bool)>,
}
impl Default for Diagnostics {
    fn default() -> Self {
        Self {
            last_report: None,
            frames: 0,
            slow_frames: 0,
            stage_total: [Duration::ZERO; 5],
            stage_max: [Duration::ZERO; 5],
            output_total: Duration::ZERO,
            output_max: Duration::ZERO,
            worst: None,
        }
    }
}
impl Diagnostics {
    pub fn record(&mut self, frame: Frame, success: bool) {
        let now = Instant::now();
        let total = now.duration_since(frame.started);
        let output = now.duration_since(frame.checkpoint);
        self.frames = self.frames.saturating_add(1);
        for index in 0..frame.stages.len() {
            self.stage_total[index] += frame.stages[index];
            self.stage_max[index] = self.stage_max[index].max(frame.stages[index]);
        }
        self.output_total += output;
        self.output_max = self.output_max.max(output);
        if total >= Duration::from_millis(100) {
            self.slow_frames += 1;
            if self.worst.as_ref().is_none_or(|old| total > old.0) {
                self.worst = Some((total, frame.stages, output, success));
            }
        }
        let Some(last) = self.last_report else {
            self.last_report = Some(now);
            return;
        };
        if now.duration_since(last) < Duration::from_secs(5) {
            return;
        }
        let average_ms = |value: Duration| value.as_secs_f64() * 1000.0 / self.frames.max(1) as f64;
        tracing::info!(
            frames = self.frames,
            slow_frames = self.slow_frames,
            map_avg_ms = average_ms(self.stage_total[2]),
            map_max_ms = self.stage_max[2].as_secs_f64() * 1000.0,
            encode_picture_avg_ms = average_ms(self.stage_total[3]),
            encode_picture_max_ms = self.stage_max[3].as_secs_f64() * 1000.0,
            lock_bitstream_avg_ms = average_ms(self.stage_total[4]),
            lock_bitstream_max_ms = self.stage_max[4].as_secs_f64() * 1000.0,
            output_avg_ms = average_ms(self.output_total),
            output_max_ms = self.output_max.as_secs_f64() * 1000.0,
            "NVENC driver stage timings"
        );
        if let Some((total, stages, output, success)) = self.worst.take() {
            tracing::warn!(
                slow_frames = self.slow_frames,
                total_ms = total.as_secs_f64() * 1000.,
                conversion_submit_ms = stages[0].as_secs_f64() * 1000.,
                cuda_input_ms = stages[1].as_secs_f64() * 1000.,
                map_input_ms = stages[2].as_secs_f64() * 1000.,
                encode_picture_ms = stages[3].as_secs_f64() * 1000.,
                lock_bitstream_ms = stages[4].as_secs_f64() * 1000.,
                output_cleanup_ms = output.as_secs_f64() * 1000.,
                success,
                "NVENC slow frame driver stages (worst sample)"
            );
        }
        self.frames = 0;
        self.slow_frames = 0;
        self.stage_total = [Duration::ZERO; 5];
        self.stage_max = [Duration::ZERO; 5];
        self.output_total = Duration::ZERO;
        self.output_max = Duration::ZERO;
        self.last_report = Some(now);
    }
}
