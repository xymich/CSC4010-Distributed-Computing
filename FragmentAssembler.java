import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FragmentAssembler {
    
    // Store incomplete fragment groups
    private Map<UUID, FragmentGroup> fragmentGroups;
    
    // Timeout for incomplete fragments (30 seconds)
    private static final long FRAGMENT_TIMEOUT = 30000;
    
    public FragmentAssembler() {
        this.fragmentGroups = new ConcurrentHashMap<>();
    }
    
    /**
     * Add a fragment and check if we can assemble the complete packet
     * Returns null if more fragments needed, or the assembled packet if complete
     */
    public NetworkPacket addFragment(NetworkPacket fragment) throws IOException, ClassNotFoundException {
        if (!fragment.isFragmented()) {
            return fragment; // Not fragmented, return as-is
        }
        
        UUID groupId = fragment.getFragmentGroupId();
        
        // Get or create fragment group
        FragmentGroup group = fragmentGroups.computeIfAbsent(
            groupId, 
            k -> new FragmentGroup(fragment.getTotalFragments())
        );
        
        // Add fragment
        group.addFragment(fragment.getFragmentIndex(), fragment);
        
        // Check if complete
        if (group.isComplete()) {
            fragmentGroups.remove(groupId);
            return group.assemble();
        }
        
        return null; // Still waiting for more fragments
    }
    
    /**
     * Clean up old incomplete fragment groups
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        fragmentGroups.entrySet().removeIf(entry -> 
            now - entry.getValue().getFirstReceived() > FRAGMENT_TIMEOUT
        );
    }
    
    /**
     * Inner class to hold a group of fragments
     */
    private static class FragmentGroup {
        private final java.util.concurrent.ConcurrentMap<Integer, NetworkPacket> fragments;
        private final java.util.concurrent.atomic.AtomicInteger receivedCount;
        private final int totalFragments;
        private final long firstReceived;
        
        public FragmentGroup(int totalFragments) {
            this.fragments = new java.util.concurrent.ConcurrentHashMap<>();
            this.receivedCount = new java.util.concurrent.atomic.AtomicInteger();
            this.totalFragments = totalFragments;
            this.firstReceived = System.currentTimeMillis();
        }
        
        public void addFragment(int index, NetworkPacket fragment) {
            NetworkPacket existing = fragments.putIfAbsent(index, fragment);
            if (existing == null) {
                receivedCount.incrementAndGet();
            }
        }
        
        public boolean isComplete() {
            return receivedCount.get() >= totalFragments;
        }
        
        public long getFirstReceived() {
            return firstReceived;
        }
        
        public NetworkPacket assemble() throws IOException, ClassNotFoundException {
            // Sort fragments by index
            List<NetworkPacket> sortedFragments = new ArrayList<>();
            for (int i = 0; i < totalFragments; i++) {
                sortedFragments.add(fragments.get(i));
            }
            
            // Combine fragment data
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessageType originalType = null;
            
            for (NetworkPacket fragment : sortedFragments) {
                PacketSerialiser.FragmentPayload fragmentPayload = 
                    (PacketSerialiser.FragmentPayload) fragment.getPayload();
                
                if (originalType == null) {
                    originalType = fragmentPayload.originalType;
                }
                
                baos.write(fragmentPayload.data);
            }
            
            // Deserialize combined data
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bais);
            Object reconstructedPayload = ois.readObject();
            
            // Get metadata from first fragment
            NetworkPacket first = sortedFragments.get(0);
            
            // Return reconstructed packet
            return new NetworkPacket(
                originalType,
                first.getSenderId(),
                first.getSenderNickname(),
                first.getLamportTimestamp(),
                reconstructedPayload
            );
        }
    }
}
