package com.nitrous.tessellate.entity

import com.nitrous.tessellate.world.World

/**
 * NHPHP Item Merging system living in `:core` (platform-independent):
 * - Scans active, non-sleeping [ItemDropEntity] drops sorted along X with zero allocations.
 * - Merges identical `itemId` drops within [ITEM_MERGE_RADIUS_BLOCKS] (1.5 blocks).
 * - Caps candidate checks per entity per tick to [MAX_MERGE_CHECKS_PER_ENTITY] (5) to avoid O(n^2) spikes.
 */
class ItemDropMerger {

    data class MergePassResult(
        val mergedCount: Int,
        val candidateChecks: Int
    )

    private val entitiesScratch = ArrayList<Entity>()
    private val dropsScratch = ArrayList<ItemDropEntity>()

    var lastMergedCount: Int = 0
        private set
    var lastCandidateChecks: Int = 0
        private set

    fun mergeNearby(world: World, minTickCount: Int = 0): MergePassResult {
        world.copyEntitiesTo(entitiesScratch)
        dropsScratch.clear()
        for (i in 0 until entitiesScratch.size) {
            val drop = entitiesScratch[i] as? ItemDropEntity ?: continue
            if (!drop.isRemoved && !drop.isSleeping && drop.tickCount >= minTickCount) {
                dropsScratch.add(drop)
            }
        }

        val count = dropsScratch.size
        if (count < 2) {
            lastMergedCount = 0
            lastCandidateChecks = 0
            return MergePassResult(mergedCount = 0, candidateChecks = 0)
        }

        // In-place insertion sort ascending by X so horizontal neighbors are contiguous
        for (i in 1 until count) {
            val key = dropsScratch[i]
            val keyX = key.position.x
            var j = i - 1
            while (j >= 0 && dropsScratch[j].position.x > keyX) {
                dropsScratch[j + 1] = dropsScratch[j]
                j--
            }
            dropsScratch[j + 1] = key
        }

        var checksTotal = 0
        var mergedTotal = 0

        for (i in 0 until count) {
            val primary = dropsScratch[i]
            if (primary.isRemoved) continue

            var checksForEntity = 0
            for (j in i + 1 until count) {
                val other = dropsScratch[j]
                if (other.isRemoved) continue

                val dx = other.position.x - primary.position.x
                if (dx > ITEM_MERGE_RADIUS_BLOCKS) break

                if (checksForEntity >= MAX_MERGE_CHECKS_PER_ENTITY) break
                checksForEntity++
                checksTotal++

                if (!primary.itemId.equals(other.itemId, ignoreCase = true)) continue

                val dy = other.position.y - primary.position.y
                val dz = other.position.z - primary.position.z
                if ((dx * dx + dy * dy + dz * dz) <= ITEM_MERGE_RADIUS_SQ) {
                    primary.absorbStack(other)
                    world.removeEntity(other)
                    mergedTotal++
                }
            }
        }

        lastMergedCount = mergedTotal
        lastCandidateChecks = checksTotal
        return MergePassResult(mergedCount = mergedTotal, candidateChecks = checksTotal)
    }

    companion object {
        const val ITEM_MERGE_RADIUS_BLOCKS: Float = 1.5f
        const val ITEM_MERGE_RADIUS_SQ: Float = ITEM_MERGE_RADIUS_BLOCKS * ITEM_MERGE_RADIUS_BLOCKS
        const val MAX_MERGE_CHECKS_PER_ENTITY: Int = 5
    }
}
