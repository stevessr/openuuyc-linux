"""Pinned xrizer compatibility: propagate reference-space errors, never fake STAGE."""
from pathlib import Path

def apply(root):
    path=Path(root)/'src/openxr_data.rs';text=path.read_text()
    old='''        let view_space = session
            .create_reference_space(xr::ReferenceSpaceType::VIEW, xr::Posef::IDENTITY)
            .unwrap();
        let [local_space_reference, local_space_adjusted] = std::array::from_fn(|_| {
            session
                .create_reference_space(xr::ReferenceSpaceType::LOCAL, xr::Posef::IDENTITY)
                .unwrap()
        });
        let [stage_space_reference, stage_space_adjusted] = std::array::from_fn(|_| {
            session
                .create_reference_space(xr::ReferenceSpaceType::STAGE, xr::Posef::IDENTITY)
                .unwrap()
        });'''
    new='''        let view_space = session
            .create_reference_space(xr::ReferenceSpaceType::VIEW, xr::Posef::IDENTITY)
            .map_err(SessionCreationError::SessionCreationFailed)?;
        let local_space_reference = session.create_reference_space(xr::ReferenceSpaceType::LOCAL, xr::Posef::IDENTITY)
            .map_err(SessionCreationError::SessionCreationFailed)?;
        let local_space_adjusted = session.create_reference_space(xr::ReferenceSpaceType::LOCAL, xr::Posef::IDENTITY)
            .map_err(SessionCreationError::SessionCreationFailed)?;
        // OpenUUYC: standing requires a real measured floor. No LOCAL-as-STAGE
        // substitution and no panic dialog if a disconnected HMD lacks STAGE.
        let stage_space_reference = session.create_reference_space(xr::ReferenceSpaceType::STAGE, xr::Posef::IDENTITY)
            .map_err(SessionCreationError::SessionCreationFailed)?;
        let stage_space_adjusted = session.create_reference_space(xr::ReferenceSpaceType::STAGE, xr::Posef::IDENTITY)
            .map_err(SessionCreationError::SessionCreationFailed)?;'''
    if text.count(old)!=1:raise RuntimeError('Pinned xrizer reference-space ABI changed')
    path.write_text(text.replace(old,new,1))
if __name__=='__main__':
    import sys
    apply(sys.argv[1])
