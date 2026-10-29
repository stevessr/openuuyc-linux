//! Suppress unsupported OSC 3008 context markers without altering other OSCs.
use std::collections::VecDeque;

#[derive(Default)]
pub(super) struct Filter {
    prefix: usize,
    body: Option<usize>,
    escape: bool,
    pub(super) ready: VecDeque<u8>,
}

impl Filter {
    pub(super) fn push(&mut self, bytes: &[u8]) {
        const PREFIX: &[u8] = b"\x1b]3008;";
        for &byte in bytes {
            if let Some(count) = self.body {
                if self.escape {
                    self.escape = false;
                    self.body = None;
                    if byte == b'\\' {
                        continue;
                    }
                    self.prefix = 1;
                } else if byte == 7 {
                    self.body = None;
                    continue;
                } else if byte == 27 {
                    self.escape = true;
                    continue;
                } else if byte >= 32 && byte != 127 && count < 8192 {
                    self.body = Some(count + 1);
                    continue;
                } else {
                    self.body = None;
                }
            }
            if byte == PREFIX[self.prefix] {
                self.prefix += 1;
                if self.prefix == PREFIX.len() {
                    self.prefix = 0;
                    self.body = Some(0);
                }
            } else {
                self.ready.extend(&PREFIX[..self.prefix]);
                self.prefix = 0;
                if byte == 27 {
                    self.prefix = 1;
                } else {
                    self.ready.push_back(byte);
                }
            }
        }
    }

    pub(super) fn finish(&mut self) {
        self.ready.extend(&b"\x1b]3008;"[..self.prefix]);
        self.prefix = 0;
        self.body = None;
        if self.escape {
            self.ready.push_back(27);
        }
        self.escape = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn every_split_preserves_other_sequences() {
        let input = b"a\x1b]3008;start=1\x1b\\b\x1b]0;title\x07c\x1b]3008;end=1\x07d";
        for split in 0..=input.len() {
            let mut f = Filter::default();
            f.push(&input[..split]);
            f.push(&input[split..]);
            f.finish();
            assert_eq!(
                f.ready.into_iter().collect::<Vec<_>>(),
                b"ab\x1b]0;title\x07cd"
            );
        }
    }
    #[test]
    fn cancelled_and_truncated_sequences() {
        let mut f = Filter::default();
        f.push(b"\x1b]3008;bad\nvisible\x1b]30");
        f.finish();
        assert_eq!(f.ready.into_iter().collect::<Vec<_>>(), b"\nvisible\x1b]30");
    }
}
