package org.ovirt.engine.core.bll;

import static java.util.stream.Collectors.toList;

import java.util.Collections;
import java.util.List;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Typed;
import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.DestroyImageParameters;
import org.ovirt.engine.core.common.asynctasks.EntityInfo;
import org.ovirt.engine.core.common.errors.EngineError;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.vdscommands.SPMGetVolumeInfoVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.Guid;

@InternalCommandAttribute
@NonTransactiveCommandAttribute
public class DestroyImageCheckCommand<T extends DestroyImageParameters>
        extends CommandBase<T> {

    @Inject
    @Typed(DestroyImageCheckCommandCallback.class)
    private Instance<DestroyImageCheckCommandCallback> callbackProvider;

    public DestroyImageCheckCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void executeCommand() {
        getParameters().setEntityInfo(new EntityInfo(VdcObjectType.Disk, getParameters().getImageGroupId()));

        List<Guid> failedGuids = getFailedVolumeIds();

        if (failedGuids.isEmpty()) {
            log.info("Requested images were successfully removed");
            setSucceeded(true);
            setCommandStatus(CommandStatus.SUCCEEDED);
            persistCommand(getParameters().getParentCommand());
        } else {
            log.error("The following images were not removed: {}", failedGuids);
        }
    }

    protected List<Guid> getFailedVolumeIds() {
        return getParameters().getImageList().stream().filter(this::volumeExists).collect(toList());
    }

    private boolean volumeExists(Guid volumeId) {
        log.debug("Checking for the existence of volume '{}' using GetVolumeInfo", volumeId);
        SPMGetVolumeInfoVDSCommandParameters params = new SPMGetVolumeInfoVDSCommandParameters(
                getParameters().getStoragePoolId(),
                getParameters().getStorageDomainId(),
                getParameters().getImageGroupId(),
                volumeId);

        params.setExpectedEngineErrors(Collections.singleton(EngineError.VolumeDoesNotExist));
        try {
            runVdsCommand(VDSCommandType.SPMGetVolumeInfo, params);
        } catch (EngineException e) {
            if (e.getVdsError().getCode() == EngineError.VolumeDoesNotExist) {
                return false;
            }
            // We can't assume the volume is gone; return true so that Live Merge fails
            log.error("Failed to determine volume '{}' existence using GetVolumeInfo", volumeId, e);
            setSucceeded(true);
            setCommandStatus(CommandStatus.ACTIVE);
            persistCommand(getParameters().getParentCommand());
        }

        return true;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.emptyList();
    }

    @Override
    public CommandCallback getCallback() {
        return callbackProvider.get();
    }
}
