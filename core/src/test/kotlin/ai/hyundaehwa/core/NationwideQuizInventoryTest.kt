package ai.hyundaehwa.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** Inventory fixture is exported from the exact JSON embedded in the APK. */
class NationwideQuizInventoryTest {
    @Test fun realLocalInventoryKeepsEveryDifficultyAndSubjectBudgetAcrossAll246Areas() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/nationwide-quiz-inventory.tsv"))
            .bufferedReader(Charsets.UTF_8).readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        val polygon = listOf(listOf(listOf(AdministrativePoint(0.0,0.0),AdministrativePoint(1.0,0.0),AdministrativePoint(1.0,1.0),AdministrativePoint(0.0,1.0),AdministrativePoint(0.0,0.0))))
        val areas = lines.groupBy { it[0] }.values.map { rows ->
            val first=rows.first()
            AdministrativeConversationArea(first[0],first[1],first[2],"","","","local",polygon,rows.map { row ->
                AdministrativeConversationTopic(row[3],"quiz",row[3],row[3],
                    listOf(AdministrativeConversationOption("a","A"),AdministrativeConversationOption("b","B"),AdministrativeConversationOption("c","C")),
                    sourceKind="official-place-fact",correctChoiceId="a",difficulty=row[4].toInt(),subject=row[5])
            })
        }
        assertEquals(246,areas.size)
        assertEquals(2354,lines.map { it[3] }.toSet().size)
        for (level in 1..3) {
            val catalog=AdministrativeConversationCatalog(AdministrativeConversationDataset("shared","shared","https://example.test",areas),Random(33))
            val shown=mutableListOf<String>()
            repeat(2) {
                for (area in areas) {
                    val inventory=catalog.available(area,"adult",level)
                    assertTrue(inventory.isNotEmpty(),area.label)
                    assertTrue(inventory.all { card -> card.difficulty==level && area.topics.any { it.id==card.id } },area.label)
                    val added=area.topics.filter { card -> card.difficulty==level && card.id.startsWith("diverse-") }
                    assertEquals(2,added.size,area.label)
                    assertEquals(2,added.map { card -> card.subject }.toSet().size,area.label)
                    assertTrue(added.none { card -> card.subject=="history" },area.label)
                    assertEquals(inventory.map { card -> card.id },catalog.available(area,"adult",level).map { card -> card.id },"reads must be stable")
                    val card=catalog.availableForPresentation(area,"adult",level).first()
                    val selection=assertNotNull(catalog.selection(area,card))
                    catalog.recordPresented(selection,"adult",level)
                    shown.add(card.subject)
                    assertTrue(shown.takeLast(5).count { subject -> subject=="history" }<=1,area.label)
                }
            }
            assertTrue(shown.toSet().size>=5,"actual inventory subject diversity")
        }
    }
}
