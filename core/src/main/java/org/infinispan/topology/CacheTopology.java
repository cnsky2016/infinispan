package org.infinispan.topology;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.infinispan.commons.marshall.InstanceReusingAdvancedExternalizer;
import org.infinispan.commons.marshall.MarshallUtil;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.marshall.core.Ids;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * The status of a cache from a distribution/state transfer point of view.
 * <p/>
 * The pending CH can be {@code null} if we don't have a state transfer in progress.
 * <p/>
 * The {@code topologyId} is incremented every time the topology changes (e.g. a member leaves, state transfer
 * starts or ends).
 * The {@code rebalanceId} is not modified when the consistent hashes are updated without requiring state
 * transfer (e.g. when a member leaves).
 *
 * @author Dan Berindei
 * @since 5.2
 */
public class CacheTopology {

   private static Log log = LogFactory.getLog(CacheTopology.class);
   private static final boolean trace = log.isTraceEnabled();

   private final int topologyId;
   private final int rebalanceId;
   private final ConsistentHash currentCH;
   private final ConsistentHash pendingCH;
   private final ConsistentHash unionCH;
   private final Phase phase;
   private List<Address> actualMembers;
   private List<PersistentUUID> persistentUUIDs;

   public CacheTopology(int topologyId, int rebalanceId, ConsistentHash currentCH, ConsistentHash pendingCH,
                        Phase phase, List<Address> actualMembers, List<PersistentUUID> persistentUUIDs) {
      this(topologyId, rebalanceId, currentCH, pendingCH, null, phase, actualMembers, persistentUUIDs);
   }

   public CacheTopology(int topologyId, int rebalanceId, ConsistentHash currentCH, ConsistentHash pendingCH,
                        ConsistentHash unionCH, Phase phase, List<Address> actualMembers, List<PersistentUUID> persistentUUIDs) {
      if (pendingCH != null && !pendingCH.getMembers().containsAll(currentCH.getMembers()) && phase != Phase.CONFLICT_RESOLUTION) {
         throw new IllegalArgumentException("A cache topology's pending consistent hash must " +
               "contain all the current consistent hash's members: currentCH=" + currentCH + ", pendingCH=" + pendingCH);
      }
      this.topologyId = topologyId;
      this.rebalanceId = rebalanceId;
      this.currentCH = currentCH;
      this.pendingCH = pendingCH;
      this.unionCH = unionCH;
      this.phase = phase;
      this.actualMembers = actualMembers;
      this.persistentUUIDs = persistentUUIDs;
   }

   public int getTopologyId() {
      return topologyId;
   }

   /**
    * The current consistent hash.
    */
   public ConsistentHash getCurrentCH() {
      return currentCH;
   }

   /**
    * The future consistent hash. Should be {@code null} if there is no rebalance in progress.
    */
   public ConsistentHash getPendingCH() {
      return pendingCH;
   }

   /**
    * The union of the current and future consistent hashes. Should be {@code null} if there is no rebalance in progress.
    */
   public ConsistentHash getUnionCH() {
      return unionCH;
   }

   /**
    * The id of the latest started rebalance.
    */
   public int getRebalanceId() {
      return rebalanceId;
   }

   /**
    * @return The nodes that are members in both consistent hashes (if {@code pendingCH != null},
    *    otherwise the members of the current CH).
    * @see #getActualMembers()
    */
   public List<Address> getMembers() {
      if (pendingCH != null)
         return pendingCH.getMembers();
      else if (currentCH != null)
         return currentCH.getMembers();
      else
         return Collections.emptyList();
   }

   /**
    * @return The nodes that are active members of the cache. It should be equal to {@link #getMembers()} when the
    *    cache is available, and a strict subset if the cache is in degraded mode.
    * @see org.infinispan.partitionhandling.AvailabilityMode
    */
   public List<Address> getActualMembers() {
      return actualMembers;
   }

   public List<PersistentUUID> getMembersPersistentUUIDs() {
      return persistentUUIDs;
   }

   /**
    * Read operations should always go to the "current" owners.
    */
   public ConsistentHash getReadConsistentHash() {
      switch (phase) {
         case NO_REBALANCE:
            assert pendingCH == null;
            assert unionCH == null;
            return currentCH;
         case CONFLICT_RESOLUTION:
         case READ_OLD_WRITE_ALL:
            assert pendingCH != null;
            assert unionCH != null;
            return currentCH;
         case READ_ALL_WRITE_ALL:
            assert pendingCH != null;
            return unionCH;
         case READ_NEW_WRITE_ALL:
            assert unionCH != null;
            return pendingCH;
      }
      return currentCH;
   }

   /**
    * When there is a rebalance in progress, write operations should go to the union of the "current" and "future" owners.
    */
   public ConsistentHash getWriteConsistentHash() {
      if (pendingCH != null) {
         if (unionCH == null)
            throw new IllegalStateException("Need a union CH when a pending CH is set");
         return unionCH;
      }

      return currentCH;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      CacheTopology that = (CacheTopology) o;

      if (topologyId != that.topologyId) return false;
      if (rebalanceId != that.rebalanceId) return false;
      if (currentCH != null ? !currentCH.equals(that.currentCH) : that.currentCH != null) return false;
      if (pendingCH != null ? !pendingCH.equals(that.pendingCH) : that.pendingCH != null) return false;
      if (unionCH != null ? !unionCH.equals(that.unionCH) : that.unionCH != null) return false;
      if (actualMembers != null ? !actualMembers.equals(that.actualMembers) : that.actualMembers != null) return false;

      return true;
   }

   @Override
   public int hashCode() {
      int result = topologyId;
      result = 31 * result + rebalanceId;
      result = 31 * result + (currentCH != null ? currentCH.hashCode() : 0);
      result = 31 * result + (pendingCH != null ? pendingCH.hashCode() : 0);
      result = 31 * result + (unionCH != null ? unionCH.hashCode() : 0);
      result = 31 * result + (actualMembers != null ? actualMembers.hashCode() : 0);
      return result;
   }

   @Override
   public String toString() {
      return "CacheTopology{" +
            "id=" + topologyId +
            ", rebalanceId=" + rebalanceId +
            ", currentCH=" + currentCH +
            ", pendingCH=" + pendingCH +
            ", unionCH=" + unionCH +
            ", phase=" + phase    +
            ", actualMembers=" + actualMembers +
            ", persistentUUIDs=" + persistentUUIDs +
            '}';
   }

   public final void logRoutingTableInformation() {
      if (trace) {
         log.tracef("Current consistent hash's routing table: %s", currentCH.getRoutingTableAsString());
         if (pendingCH != null) log.tracef("Pending consistent hash's routing table: %s", pendingCH.getRoutingTableAsString());
         if (unionCH != null) log.tracef("Union consistent hash's routing table: %s", unionCH.getRoutingTableAsString());
      }
   }

   public Phase getPhase() {
      return phase;
   }


   public static class Externalizer extends InstanceReusingAdvancedExternalizer<CacheTopology> {
      @Override
      public void doWriteObject(ObjectOutput output, CacheTopology cacheTopology) throws IOException {
         output.writeInt(cacheTopology.topologyId);
         output.writeInt(cacheTopology.rebalanceId);
         output.writeObject(cacheTopology.currentCH);
         output.writeObject(cacheTopology.pendingCH);
         output.writeObject(cacheTopology.unionCH);
         output.writeObject(cacheTopology.actualMembers);
         output.writeObject(cacheTopology.persistentUUIDs);
         MarshallUtil.marshallEnum(cacheTopology.phase, output);
      }

      @Override
      public CacheTopology doReadObject(ObjectInput unmarshaller) throws IOException, ClassNotFoundException {
         int topologyId = unmarshaller.readInt();
         int rebalanceId = unmarshaller.readInt();
         ConsistentHash currentCH = (ConsistentHash) unmarshaller.readObject();
         ConsistentHash pendingCH = (ConsistentHash) unmarshaller.readObject();
         ConsistentHash unionCH = (ConsistentHash) unmarshaller.readObject();
         List<Address> actualMembers = (List<Address>) unmarshaller.readObject();
         List<PersistentUUID> persistentUUIDs = (List<PersistentUUID>) unmarshaller.readObject();
         Phase phase = MarshallUtil.unmarshallEnum(unmarshaller, Phase::valueOf);
         return new CacheTopology(topologyId, rebalanceId, currentCH, pendingCH, unionCH, phase, actualMembers, persistentUUIDs);
      }

      @Override
      public Integer getId() {
         return Ids.CACHE_TOPOLOGY;
      }

      @Override
      public Set<Class<? extends CacheTopology>> getTypeClasses() {
         return Collections.<Class<? extends CacheTopology>>singleton(CacheTopology.class);
      }
   }

   /**
    * Phase of the rebalance process. Using four phases guarantees these properties:
    *
    * 1. T(x+1).writeCH contains all nodes from Tx.readCH (this is the requirement for ISPN-5021)
    * 2. Tx.readCH and T(x+1).readCH has non-empty subset of nodes (that will allow no blocking for read commands
    *    && reading only entries node owns according to readCH)
    *
    * Old entries should be wiped out only after coming to the {@link #NO_REBALANCE} phase.
    */
   public enum Phase {
      /**
       * Only currentCH should be set, this works as both readCH and writeCH
       */
      NO_REBALANCE,
      /**
       * Interim state between NO_REBALANCE -> READ_OLD_WRITE_ALL
       * readCh is set locally using previous Topology (of said node) readCH, whilst writeCH contains all members after merge
       */
      CONFLICT_RESOLUTION,
      /**
       * Used during state transfer: readCH == currentCH, writeCH = unionCH
       */
      READ_OLD_WRITE_ALL,
      /**
       * Used after state transfer completes: readCH == writeCH = unionCH
       */
      READ_ALL_WRITE_ALL,
      /**
       * Intermediate state that prevents ISPN-5021: readCH == pendingCH, writeCH = unionCH
       */
      READ_NEW_WRITE_ALL;

      private static final Phase[] values = Phase.values();

      public static Phase valueOf(int ordinal) {
         return values[ordinal];
      }

      public Phase advance() {
         return values[(ordinal() + 1) % values.length];
      }
   }
}
