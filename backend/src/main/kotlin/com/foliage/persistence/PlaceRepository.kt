package com.foliage.persistence

import com.foliage.domain.Place
import com.foliage.ingest.places.PlaceKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Types

@Repository
class PlaceRepository(private val jdbc: JdbcTemplate) {

    fun upsertAll(places: List<Place>): Int {
        if (places.isEmpty()) return 0

        val sql = """
            INSERT INTO place
                (geoname_id, name, state_code, latitude, longitude, population, kind, h3)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name        = VALUES(name),
                state_code  = VALUES(state_code),
                latitude    = VALUES(latitude),
                longitude   = VALUES(longitude),
                population  = VALUES(population),
                kind        = VALUES(kind),
                h3          = VALUES(h3),
                ingested_at = NOW(6)
        """.trimIndent()

        val counts = jdbc.batchUpdate(sql, places, JDBC_BATCH_SIZE) { ps: PreparedStatement, p: Place ->
            ps.setInt(1, p.geonameId)
            ps.setString(2, p.name)
            p.stateCode?.let { ps.setString(3, it) } ?: ps.setNull(3, Types.CHAR)
            ps.setDouble(4, p.latitude)
            ps.setDouble(5, p.longitude)
            ps.setInt(6, p.population)
            ps.setString(7, p.kind.name)
            ps.setLong(8, p.h3)
        }
        return counts.sumOf { it.size }
    }

    /**
     * Places that fall inside a hexagon the grid actually covers.
     *
     * The table holds every US place regardless, so that expanding the grid
     * lights them up without re-ingesting 2.24 million rows. This join is what
     * decides which of them are useful to a visitor today — offering a search
     * result with no forecast behind it would be worse than not listing it.
     */
    fun findInGrid(): List<Place> = jdbc.query(
        """
        SELECT p.geoname_id, p.name, p.state_code, p.latitude, p.longitude,
               p.population, p.kind, p.h3
        FROM place p
        JOIN cell c ON c.h3 = p.h3
        ORDER BY p.population DESC, p.name
        """.trimIndent(),
    ) { rs, _ ->
        Place(
            geonameId = rs.getInt("geoname_id"),
            name = rs.getString("name"),
            stateCode = rs.getString("state_code"),
            latitude = rs.getDouble("latitude"),
            longitude = rs.getDouble("longitude"),
            population = rs.getInt("population"),
            kind = PlaceKind.valueOf(rs.getString("kind")),
            h3 = rs.getLong("h3"),
        )
    }

    fun count(): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM place", Long::class.java) ?: 0

    fun countInGrid(): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM place p JOIN cell c ON c.h3 = p.h3",
        Long::class.java,
    ) ?: 0

    fun countByKindInGrid(): Map<String, Long> = jdbc.query(
        """
        SELECT p.kind, COUNT(*) n FROM place p JOIN cell c ON c.h3 = p.h3
        GROUP BY p.kind
        """.trimIndent(),
    ) { rs, _ -> rs.getString("kind") to rs.getLong("n") }.toMap()
}
