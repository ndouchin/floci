package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.model.Device;

import java.util.List;
import java.util.Map;

/**
 * The Linux process options a container is created with, as {@code docker run} names them:
 * {@code --cap-add}, {@code --cap-drop}, {@code --device}, {@code --shm-size}, {@code --tmpfs},
 * {@code --memory-swap} and {@code --memory-swappiness}; {@code --init} is
 * {@link ContainerSpec#init()}. {@link #NONE} leaves every one of them to the daemon.
 *
 * @param capAdd capabilities added to the daemon's default set, named without {@code CAP_}
 * @param capDrop capabilities dropped from the daemon's default set, named without {@code CAP_}
 * @param devices host devices exposed inside the container
 * @param shmSizeBytes size of {@code /dev/shm} in bytes (null = daemon default)
 * @param tmpfs tmpfs mounts, keyed by container path, each with Docker's option string
 *        (for example {@code "noexec,size=64m"})
 * @param memorySwapBytes memory plus swap the container may use, in bytes; equal to the memory
 *        limit means no swap (null = daemon default)
 * @param memorySwappiness swappiness from 0 to 100 (null = daemon default)
 * @param sysctls namespaced kernel parameters set in the container ({@code --sysctl})
 * @param dnsSearch search domains of the container's resolver ({@code --dns-search}); empty leaves the daemon's,
 *        which copies the host's
 * @param dnsOptions options of the container's resolver ({@code --dns-option}); empty leaves the daemon's
 */
public record LinuxOptions(
        List<String> capAdd,
        List<String> capDrop,
        List<Device> devices,
        Long shmSizeBytes,
        Map<String, String> tmpfs,
        Long memorySwapBytes,
        Long memorySwappiness,
        Map<String, String> sysctls,
        List<String> dnsSearch,
        List<String> dnsOptions
) {
    public static final LinuxOptions NONE =
            new LinuxOptions(List.of(), List.of(), List.of(), null, Map.of(), null, null, Map.of(), List.of(), List.of());

    public LinuxOptions {
        if (sysctls == null) {
            sysctls = Map.of();
        }
        if (dnsSearch == null) {
            dnsSearch = List.of();
        }
        if (dnsOptions == null) {
            dnsOptions = List.of();
        }
    }
}
