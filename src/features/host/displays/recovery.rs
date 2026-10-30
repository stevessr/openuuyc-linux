//! Restore our changes without overwriting later changes by other owners.
use super::*;
use crate::platform::display::recovery as native;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub(super) struct ModeChange {
    pub identity: String,
    pub before: Option<SavedTarget>,
    pub after: Option<SavedTarget>,
    pub pending: bool,
}
#[cfg(not(windows))]
#[derive(Clone)]
pub(super) struct ModeCheckpoint {
    changes: Option<Vec<ModeChange>>,
    desired: Option<SavedTopology>,
    applied: Option<SavedTopology>,
}

pub(super) fn same_target(a: Option<&SavedTarget>, b: Option<&SavedTarget>) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => same_mode(a, b),
        (None, None) => true,
        _ => false,
    }
}

fn record_changes(changes: &mut Vec<ModeChange>, current: &SavedTopology, desired: &SavedTopology) {
    // An external writer ends the old lease, even if a later remote request
    // happens to choose the same mode again.
    changes.retain(|c| {
        same_target(
            current.targets.iter().find(|t| t.identity == c.identity),
            c.after.as_ref(),
        )
    });
    let ids: std::collections::BTreeSet<_> = current
        .targets
        .iter()
        .chain(&desired.targets)
        .map(|t| t.identity.clone())
        .collect();
    for identity in ids {
        let before = current.targets.iter().find(|t| t.identity == identity);
        let after = desired.targets.iter().find(|t| t.identity == identity);
        if same_target(before, after) {
            continue;
        }
        if let Some(change) = changes.iter_mut().find(|c| c.identity == identity) {
            change.after = after.cloned();
            change.pending = true;
        } else {
            changes.push(ModeChange {
                identity,
                before: before.cloned(),
                after: after.cloned(),
                pending: true,
            });
        }
    }
}

fn same_mode(a: &SavedTarget, b: &SavedTarget) -> bool {
    (
        a.width,
        a.height,
        a.left,
        a.top,
        a.rotation,
        a.refresh_numerator,
        a.refresh_denominator,
        a.scan_line_ordering,
    ) == (
        b.width,
        b.height,
        b.left,
        b.top,
        b.rotation,
        b.refresh_numerator,
        b.refresh_denominator,
        b.scan_line_ordering,
    )
}

fn plan_restore(
    journal: &Journal,
    baseline: &SavedTopology,
    current: &SavedTopology,
    temporary: bool,
) -> (SavedTopology, Vec<String>) {
    let expected = journal.desired.as_ref().or(journal.applied.as_ref());
    let mut restore = SavedTopology::default();
    let mut restored = Vec::new();
    let changes = journal.mode_changes.as_deref();
    for original in &baseline.targets {
        let now = current
            .targets
            .iter()
            .find(|t| t.identity == original.identity);
        let after =
            expected.and_then(|s| s.targets.iter().find(|t| t.identity == original.identity));
        if let Some(changes) = changes {
            let change = changes.iter().find(|c| c.identity == original.identity);
            // A connection-start snapshot alone never grants restoration rights.
            if let Some(change) = change.filter(|c| same_target(now, c.after.as_ref())) {
                restored.push(change.identity.clone());
                let original = if temporary {
                    Some(original)
                } else {
                    change.before.as_ref()
                };
                if let Some(original) = original {
                    restore.targets.push(original.clone());
                }
            } else if let Some(now) = now {
                let mut keep = now.clone();
                keep.source_group = format!("current/{}", keep.source_group);
                restore.targets.push(keep);
            }
            continue;
        }
        // Older recovery records have no exact target ledger. A mode that
        // never differed from its baseline is not an owned modification.
        match (now, after) {
            (Some(now), Some(after)) if same_mode(original, after) => {
                let mut keep = now.clone();
                keep.source_group = format!("current/{}", keep.source_group);
                restore.targets.push(keep);
            }
            (Some(now), Some(after)) if !same_mode(now, after) => {
                let mut keep = now.clone();
                keep.source_group = format!("current/{}", keep.source_group);
                restore.targets.push(keep);
            }
            (None, Some(_)) => (), // The user unplugged/disabled it after our change.
            (None, None) => restore.targets.push(original.clone()),
            (Some(_), Some(_)) => restore.targets.push(original.clone()),
            (Some(now), None) => {
                let mut keep = now.clone();
                keep.source_group = format!("current/{}", keep.source_group);
                restore.targets.push(keep);
            }
        }
    }
    for now in &current.targets {
        if !baseline.targets.iter().any(|t| t.identity == now.identity)
            && !journal
                .owned
                .iter()
                .any(|o| o.identity.as_deref() == Some(now.identity.as_str()))
        {
            let mut keep = now.clone();
            keep.source_group = format!("current/{}", keep.source_group);
            restore.targets.push(keep);
        }
    }
    (restore, restored)
}

impl State {
    pub(super) fn restore_layout(
        &self,
        baseline: &SavedTopology,
        dpi: &[(String, u32)],
        temporary: bool,
    ) -> Result<Vec<String>> {
        let current = Topology::query(true)?.snapshot()?;
        let (restore, restored) = plan_restore(&self.journal, baseline, &current, temporary);
        let unchanged = restore.targets.len() == current.targets.len()
            && restore.targets.iter().all(|a| {
                current
                    .targets
                    .iter()
                    .any(|b| a.identity == b.identity && same_mode(a, b))
            });
        if !restore.targets.is_empty() && !unchanged {
            let missing = restore.apply()?;
            if !missing.is_empty() {
                tracing::warn!(
                    targets = missing.len(),
                    "unavailable display targets omitted during recovery"
                );
            }
        }
        let targets = Topology::query(true)?.targets()?;
        for (identity, original) in dpi {
            let expected = self
                .journal
                .desired_dpi
                .iter()
                .chain(&self.journal.applied_dpi)
                .find(|(id, _)| id == identity)
                .map(|(_, v)| *v);
            if let Some(target) = targets.iter().find(|t| &t.identity == identity) {
                if target
                    .dpi
                    .as_ref()
                    .is_some_and(|d| Some(d.current) == expected && d.current != *original)
                {
                    target.set_dpi(*original, || true)?;
                }
            }
        }
        Ok(restored)
    }
    pub(super) fn intend_layout(&mut self, layout: &SavedTopology) -> Result<()> {
        let current = Topology::query(true)?.snapshot()?;
        self.save_layout_intent(&current, layout)
    }
    fn save_layout_intent(
        &mut self,
        current: &SavedTopology,
        layout: &SavedTopology,
    ) -> Result<()> {
        if let Some(changes) = self.journal.mode_changes.as_mut() {
            record_changes(changes, current, layout);
            // Targets first seen after connection start still need their actual
            // pre-mutation baseline for explicit layout recovery.
            for change in changes.iter() {
                if let Some(before) = &change.before {
                    if !self
                        .journal
                        .baseline
                        .targets
                        .iter()
                        .any(|t| t.identity == change.identity)
                        && !self
                            .journal
                            .owned
                            .iter()
                            .any(|o| o.identity.as_deref() == Some(change.identity.as_str()))
                    {
                        self.journal.baseline.targets.push(before.clone());
                    }
                }
            }
        }
        self.journal.desired = Some(layout.clone());
        self.save()
    }
    #[cfg(not(windows))]
    pub(super) fn mode_checkpoint(&self) -> ModeCheckpoint {
        ModeCheckpoint {
            changes: self.journal.mode_changes.clone(),
            desired: self.journal.desired.clone(),
            applied: self.journal.applied.clone(),
        }
    }
    #[cfg(not(windows))]
    pub(super) fn restore_mode_checkpoint(&mut self, checkpoint: ModeCheckpoint) -> Result<()> {
        self.journal.mode_changes = checkpoint.changes;
        self.journal.desired = checkpoint.desired;
        self.journal.applied = checkpoint.applied;
        self.save()
    }
    #[cfg(not(windows))]
    pub(super) fn observe_pending_mode(&mut self, observed: &SavedTopology) -> Result<()> {
        if let Some(changes) = self.journal.mode_changes.as_mut() {
            for change in changes.iter_mut().filter(|change| change.pending) {
                let expected = change.after.as_ref();
                let current = observed
                    .targets
                    .iter()
                    .find(|target| target.identity == change.identity);
                if let (Some(expected), Some(current)) = (expected, current)
                    && (
                        expected.width,
                        expected.height,
                        expected.left,
                        expected.top,
                        expected.rotation,
                    ) == (
                        current.width,
                        current.height,
                        current.left,
                        current.top,
                        current.rotation,
                    )
                {
                    change.after = Some(current.clone());
                }
            }
        }
        self.journal.desired = Some(observed.clone());
        self.save()
    }
    #[cfg(not(windows))]
    pub(super) fn rollback_mode_transaction(
        &mut self,
        before: &SavedTopology,
        after: &SavedTopology,
        checkpoint: ModeCheckpoint,
    ) -> Result<()> {
        let mut transaction_changes = Vec::new();
        record_changes(&mut transaction_changes, before, after);
        self.journal.mode_changes = Some(transaction_changes.clone());
        self.journal.desired = Some(after.clone());
        self.save()?;

        match self.restore_layout(before, &[], false) {
            Ok(_) => self.restore_mode_checkpoint(checkpoint),
            Err(error) => {
                let current = Topology::query(true)?.snapshot()?;
                let mut changes = checkpoint.changes.unwrap_or_default();
                changes.retain(|change| {
                    same_target(
                        before
                            .targets
                            .iter()
                            .find(|target| target.identity == change.identity),
                        change.after.as_ref(),
                    )
                });
                for change in transaction_changes {
                    let before_target = before
                        .targets
                        .iter()
                        .find(|target| target.identity == change.identity);
                    let after_target = after
                        .targets
                        .iter()
                        .find(|target| target.identity == change.identity);
                    let current_target = current
                        .targets
                        .iter()
                        .find(|target| target.identity == change.identity);
                    if same_target(current_target, before_target) {
                        continue;
                    }
                    if same_target(current_target, after_target) {
                        if let Some(previous) = changes
                            .iter_mut()
                            .find(|previous| previous.identity == change.identity)
                        {
                            previous.after = current_target.cloned();
                            previous.pending = true;
                        } else {
                            changes.push(ModeChange {
                                identity: change.identity,
                                before: before_target.cloned(),
                                after: current_target.cloned(),
                                pending: true,
                            });
                        }
                    } else {
                        // A partially restored or externally changed output is
                        // no longer ours to move again during session cleanup.
                        changes.retain(|previous| previous.identity != change.identity);
                    }
                }
                self.journal.mode_changes = Some(changes);
                self.journal.desired = Some(current);
                self.journal.applied = checkpoint.applied;
                self.save()?;
                Err(error.context("display-mode rollback failed; recovery intent remains pending"))
            }
        }
    }
    #[cfg(not(windows))]
    pub(super) fn intend_mode(
        &mut self,
        identity: &str,
        width: u32,
        height: u32,
    ) -> Result<SavedTopology> {
        let current = Topology::query(true)?.snapshot()?;
        let mut layout = current.clone();
        let index = layout
            .targets
            .iter()
            .position(|target| target.identity == identity)
            .context("显示目标已断开")?;
        let old = layout.targets[index].clone();
        let (dx, dy) = (
            i64::from(width) - i64::from(old.width),
            i64::from(height) - i64::from(old.height),
        );
        let (old_right, old_bottom) = (
            i64::from(old.left) + i64::from(old.width),
            i64::from(old.top) + i64::from(old.height),
        );
        for (other_index, other) in layout.targets.iter_mut().enumerate() {
            if other_index == index {
                continue;
            }
            if i64::from(other.left) >= old_right {
                other.left = i32::try_from(i64::from(other.left) + dx)?;
            }
            if i64::from(other.top) >= old_bottom {
                other.top = i32::try_from(i64::from(other.top) + dy)?;
            }
        }
        layout.targets[index].width = width;
        layout.targets[index].height = height;
        // The intent includes the same adjacent-output movement as RandR apply,
        // so the recovery guard can restore the complete transaction.
        self.save_layout_intent(&current, &layout)?;
        Ok(current)
    }
    #[cfg(windows)]
    pub(super) fn intend_mode(&mut self, identity: &str, width: u32, height: u32) -> Result<()> {
        let current = Topology::query(true)?.snapshot()?;
        let mut layout = current.clone();
        let target = layout
            .targets
            .iter_mut()
            .find(|target| target.identity == identity)
            .context("显示目标已断开")?;
        target.width = width;
        target.height = height;
        // Compare one snapshot with its one-target edit. A second inventory
        // read must not turn another screen's concurrent game change into ours.
        self.save_layout_intent(&current, &layout)
    }
    pub(super) fn intend_dpi(&mut self, identity: &str, dpi: u32) -> Result<()> {
        if let Some((_, current)) = current_dpi()?.into_iter().find(|(id, _)| id == identity) {
            if !self
                .journal
                .applied_dpi
                .iter()
                .any(|(id, value)| id == identity && *value == current)
            {
                self.journal.dpi.retain(|(id, _)| id != identity);
                self.journal.dpi.push((identity.into(), current));
            }
        }
        self.journal.desired_dpi.retain(|(id, _)| id != identity);
        self.journal.desired_dpi.push((identity.into(), dpi));
        self.save()
    }
}

pub(crate) fn watch(token: &str) -> Result<()> {
    let token = uuid::Uuid::parse_str(token)?.to_string();
    let path = store::root()?
        .join("recovery")
        .join(format!("{token}.json"));
    let journal: Journal = store::read(&path)?.context("显示恢复记录不存在")?;
    ensure!(journal.token == token, "显示恢复标识不匹配");
    let parent = native::parent(journal.owner_pid, journal.owner_birth)?;
    native::ready(&token)?;
    while !native::exited(&parent, 500)? {
        let Some(journal) = store::read::<Journal>(&path)? else {
            return Ok(());
        };
        if journal.retired {
            break;
        }
    }
    recover(&path)
}

fn recover(path: &std::path::Path) -> Result<()> {
    let _serial = native::Serial::acquire()?;
    let Some(journal) = store::read::<Journal>(path)? else {
        return Ok(());
    };
    ensure!(
        journal.retired || !native::owner_alive(journal.owner_pid, journal.owner_birth)?,
        "显示拥有者仍在运行"
    );
    let mut state = State {
        journal,
        path: path.into(),
        preferences: Preference::default(),
        has_saved_preferences: false,
        preference_path: None,
        driver: None,
        #[cfg(windows)]
        fallback_pin: fallback::pin()?,
        #[cfg(not(windows))]
        fallback_pin: None,
        render_adapter: None,
        dirty: true,
        restoring: true,
        // 恢复是另一个进程在做 X11 拓扑的回滚，和本进程的虚拟屏租约无关；
        // Linux 上也不会写这份日志，所以这里没有东西要还。
        #[cfg(not(windows))]
        virtual_display: None,
    };
    state.cleanup()
}

pub(crate) fn recover_abandoned() -> Result<()> {
    let directory = store::root()?.join("recovery");
    if !directory.exists() {
        return Ok(());
    }
    for entry in std::fs::read_dir(directory)? {
        let path = entry?.path();
        if path.extension().is_none_or(|e| e != "json") {
            continue;
        }
        let Some(journal) = store::read::<Journal>(&path)? else {
            continue;
        };
        if !journal.retired && native::owner_alive(journal.owner_pid, journal.owner_birth)? {
            continue;
        }
        recover(&path)?;
    }
    Ok(())
}
