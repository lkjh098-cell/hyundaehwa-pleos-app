package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class AdministrativeConversationTest {
    private fun square(w: Double, s: Double, e: Double, n: Double) = listOf(
        AdministrativePoint(w,s), AdministrativePoint(e,s), AdministrativePoint(e,n), AdministrativePoint(w,n), AdministrativePoint(w,s))
    private fun topics(prefix: String) = listOf("quiz-a", "quiz-b", "balance").map { kind ->
        val format = if (kind.startsWith("quiz")) "quiz" else "balance"
        AdministrativeConversationTopic("$prefix-$kind",format,"$format 제목","${prefix} 중구의 $format 질문인가요?",
            listOf(AdministrativeConversationOption("a","첫 번째"),AdministrativeConversationOption("b","두 번째")),
            sourceTitle="공식 콘텐츠 자료",sourceUrl="https://example.gov/topic",dataTier="verified-regional",
            sourceKind=if(format=="quiz") "official-place-fact" else LocalContentEligibility.BALANCE_SOURCE) }
    private fun area(id: String, label: String, polygon: List<List<AdministrativePoint>>, other: List<List<AdministrativePoint>>? = null) =
        AdministrativeConversationArea(id,id,label,"광역시","","중구","district",listOfNotNull(polygon,other),topics(id))
    private fun data(areas: List<AdministrativeConversationArea>) = AdministrativeConversationDataset("2026-10-01","행정경계 자료","https://example.gov/boundary",areas)
    private fun catalog() = AdministrativeConversationCatalog(data(listOf(
        area("a","서울특별시 중구",listOf(square(126.9,37.5,127.0,37.6))),
        area("b","부산광역시 중구",listOf(square(129.0,35.0,129.1,35.1))),
        area("c","일반시",listOf(square(127.0,36.0,127.2,36.2),square(127.05,36.05,127.1,36.1)),listOf(square(127.3,36.0,127.4,36.1)))
    )))
    @Test fun coordinatesChooseDisambiguatedAdministrativeAreasAndRejectUnknownPoints() {
        val c=catalog();assertEquals(3,c.supportedAreaCount)
        assertEquals("a",c.areaAt(126.95,37.55)?.id);assertEquals("b",c.areaAt(129.05,35.05)?.id)
        for (p in listOf(null to 37.55,126.95 to null,Double.NaN to 37.55,Double.POSITIVE_INFINITY to 37.55,181.0 to 37.55,126.95 to 91.0,130.0 to 30.0)) assertNull(c.areaAt(p.first,p.second))
    }
    @Test fun holeAndOffshoreUnknownStayUnknownButSeparatePolygonIslandIsSupported() {
        val c=catalog();assertEquals("c",c.areaAt(127.02,36.02)?.id)
        assertNull(c.areaAt(127.075,36.075));assertEquals("c",c.areaAt(127.35,36.05)?.id)
        assertNull(c.areaAt(127.25,36.05))
    }
    @Test fun sharedBorderSelectsOneDeterministicAreaAndDoesNotInferFromDestinationLabels() {
        val first=area("a","첫 구",listOf(square(0.0,0.0,1.0,1.0)));val second=area("b","다음 구",listOf(square(1.0,0.0,2.0,1.0)))
        for (areas in listOf(listOf(first,second),listOf(second,first))) {
            val c=AdministrativeConversationCatalog(data(areas));assertEquals("a",c.areaAt(1.0,0.5)?.id);assertEquals("b",c.areaAt(1.01,0.5)?.id)
        }
    }
    @Test fun catalogQueueChangesOnlyOnPresentationAndRetainsIndependentAreaInventories() {
        val c=catalog()
        val first=checkNotNull(c.select(126.95,37.55,true,"quiz"))
        repeat(20) { assertEquals(first.topic.id,c.select(126.95,37.55,true,"quiz")?.topic?.id) }
        val other=checkNotNull(c.select(129.05,35.05,true,"quiz"))
        c.recordPresented(first)
        assertNotEquals(first.topic.id,c.select(126.95,37.55,true,"quiz")?.topic?.id)
        assertEquals(other.topic.id,c.select(129.05,35.05,true,"quiz")?.topic?.id)
        assertNull(c.select(126.95,37.55,false))
    }
    @Test fun explicitFormatsAndLegacyAliasesOnlyExposeVerifiedQuizAndNewLocalBalance() {
        val c=catalog()
        for ((type,format) in listOf("balance" to "balance","preferences" to "balance","quiz" to "quiz","travel" to "quiz"))
            assertEquals(format,c.select(126.95,37.55,true,type)?.topic?.format)
        assertNull(c.select(126.95,37.55,true,"conversation"));assertNull(c.select(126.95,37.55,true,"everyday"))
    }
    @Test fun malformedOrIncompletePublicDatasetDoesNotCreateUnsupportedCoverage() {
        val good=area("a","정상",listOf(square(0.0,0.0,1.0,1.0)))
        val c=AdministrativeConversationCatalog(data(listOf(good.copy(topics=good.topics.take(2)),good.copy(id="",label=""),good.copy(polygons=emptyList()))))
        assertEquals(1,c.supportedAreaCount);assertNull(c.select(0.5,0.5,true,"conversation")); assertNotNull(c.select(0.5,0.5,true,"quiz"))
    }
    @Test fun cardsRotateOnlyAfterCommitAndAreFilteredByAudienceDifficultyAndSpecificity() {
        val polygon = listOf(square(0.0,0.0,1.0,1.0))
        val base = area("a", "지역", polygon)
        fun quiz(id:String, audience:Set<String>, difficulty:Int, specificity:Int) =
            AdministrativeConversationTopic(id,"quiz",id,"문제 $id",listOf(AdministrativeConversationOption("a","하나"),AdministrativeConversationOption("b","둘")),
                sourceKind="official-place-fact",audiences=audience,difficulty=difficulty,specificity=specificity,contentVersion="test")
        val bank = listOf(quiz("adult-a",setOf("adult"),3,2), quiz("adult-b",setOf("adult"),3,2),
            quiz("family",setOf("family"),2,2),quiz("province",setOf("adult"),3,1))
        val c = AdministrativeConversationCatalog(data(listOf(base.copy(topics=bank))))
        val first=checkNotNull(c.select(0.5,0.5,true,"quiz","adult",3))
        assertTrue(first.topic.id in setOf("adult-a","adult-b"))
        assertEquals(first.topic.id,c.select(0.5,0.5,true,"quiz","adult",3)?.topic?.id)
        c.recordPresented(first,"adult",3)
        assertNotEquals(first.topic.id,c.select(0.5,0.5,true,"quiz","adult",3)?.topic?.id)
        assertEquals("family",c.select(0.5,0.5,true,"quiz","family",2)?.topic?.id)
        assertNull(c.select(0.5,0.5,false,"quiz")); assertNull(c.select(0.5,0.5,true,"balance"))
    }
    @Test fun explicitDifficultyAndAudienceAreIndependentAndDoNotSubstituteOtherLevels() {
        val polygon = listOf(square(0.0,0.0,1.0,1.0))
        val base = area("a", "지역", polygon)
        fun question(id:String, level:Int) = AdministrativeConversationTopic(id,"quiz",id,"문제",listOf(
            AdministrativeConversationOption("a","하나"),AdministrativeConversationOption("b","둘")),
            sourceKind="official-place-fact",audiences=setOf("family", "adult"),difficulty=level,specificity=2)
        val c = AdministrativeConversationCatalog(data(listOf(base.copy(topics=listOf(question("low",1),question("mid",2),question("high",3))))))
        for ((level,id) in listOf(1 to "low",2 to "mid",3 to "high")) {
            assertEquals(id,c.select(0.5,0.5,true,"quiz","adult",level)?.topic?.id)
            assertEquals(id,c.select(0.5,0.5,true,"quiz","family",level)?.topic?.id)
        }
        val mediumOnly = AdministrativeConversationCatalog(data(listOf(base.copy(topics=listOf(question("mid",2))))))
        assertNull(mediumOnly.select(0.5,0.5,true,"quiz","adult",1))
        assertNull(mediumOnly.select(0.5,0.5,true,"quiz","adult",3))
    }
    @Test fun aSingleQuizIsNotRepeatedUntilAnotherCardHasBeenPresented() {
        val c0=catalog(); val area=checkNotNull(c0.areaAt(126.95,37.55))
        val q=area.topics.first { it.format=="quiz" }
        val c=AdministrativeConversationCatalog(data(listOf(area.copy(topics=listOf(q)+area.topics.filter { it.format=="balance" }))))
        val first=checkNotNull(c.select(126.95,37.55,true,"quiz"))
        c.recordPresented(first)
        assertNull(c.select(126.95,37.55,true,"quiz"))
        c.recordPresented(checkNotNull(c.select(126.95,37.55,true,"balance")))
        assertEquals(first.topic.id,c.select(126.95,37.55,true,"quiz")?.topic?.id)
    }
    @Test fun gleoResponseHasTheSelectedFormatAndSeparateContentAndBoundarySourcesWithoutCoordinates() {
        val selection=checkNotNull(catalog().select(126.95,37.55,true,"quiz"))
        val result=checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC,"quiz"),false,true,true,context=selection.gleoContext()))
        assertEquals("success",result.status);assertEquals("quiz",result.data["format"]);assertEquals("quiz",result.data["topicType"])
        assertEquals("서울특별시 중구",result.data["regionLabel"]);assertEquals("공식 콘텐츠 자료",result.data["sourceTitle"])
        assertEquals("행정경계 자료",result.data["locationSourceTitle"]);assertEquals("2026-10-01",result.data["boundaryVintage"])
        assertTrue(result.topic!!.contains("첫 번째"));assertFalse(result.data.keys.any { it in listOf("latitude","longitude","address","coordinates","destination") })
        assertFalse(result.data.toString().contains("126.95"));assertFalse(result.data.toString().contains("37.55"))
    }
    @Test fun newRegionalFormatsRetainQuietFocusAndMalformedActionGuards() {
        val context=checkNotNull(catalog().select(126.95,37.55,true)).gleoContext()
        for (type in listOf("balance","quiz","conversation")) {
            val quiet=checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC,type),true,true,true,context=context));assertEquals("fail",quiet.status);assertNull(quiet.topic)
            val focus=checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC,type),false,true,true,true,context));assertEquals("silent",focus.mode);assertNull(focus.topic)
        }
        assertEquals("fail",GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC,"quiz",malformed=true),false,true,true,context=context)?.status)
    }
    @Test fun onlyPlaceFactsEnterEveryQuizDeliveryPath() {
        val base = area("a", "지역", listOf(square(0.0, 0.0, 1.0, 1.0)))
        val local = base.topics.first { it.format == "quiz" }
        val blocked = listOf("official-general-fact", "derived-administrative-geography", "editorial-preference", "unknown")
            .mapIndexed { index, kind -> local.copy(id = "blocked-$index", sourceKind = kind) }
        val c = AdministrativeConversationCatalog(data(listOf(base.copy(topics = blocked + local))))
        val area = checkNotNull(c.areaAt(0.5, 0.5))
        assertEquals(listOf(local.id), c.available(area).filter { it.format == "quiz" }.map { it.id })
        assertEquals(listOf(local.id), c.engineTopics(area).filter { it.type == TopicType.QUIZ }.map { it.id })
        assertEquals(local.id, c.select(0.5, 0.5, true, "quiz")?.topic?.id)
        blocked.forEach { assertFalse(c.recordPresentedById(area.id, it.id)) }
        val empty = AdministrativeConversationCatalog(data(listOf(area.copy(topics = blocked))))
        assertNull(empty.select(0.5, 0.5, true, "quiz"))
        assertTrue(empty.engineTopics(checkNotNull(empty.areaAt(0.5, 0.5))).isEmpty())
    }

    @Test fun shuffledChoicesAreStableUntilCommitAndKeepCorrectIdAndSpokenAnswerIndex() {
        val base=area("a","지역",listOf(square(0.0,0.0,1.0,1.0)))
        val quizzes=(0..3).map { n -> AdministrativeConversationTopic("q$n","quiz","퀴즈","문제",listOf(
            AdministrativeConversationOption("correct","정답 보기"),AdministrativeConversationOption("wrong","오답 보기")),
            sourceKind="official-place-fact",correctChoiceId="correct",explanation="검증한 해설") }
        val c=AdministrativeConversationCatalog(data(listOf(base.copy(topics=quizzes))),kotlin.random.Random(14))
        val first=checkNotNull(c.select(0.5,0.5,true,"quiz"))
        repeat(30) { assertEquals(first.topic,c.select(0.5,0.5,true,"quiz")?.topic) }
        assertEquals("correct",first.topic.correctChoiceId)
        val index=first.topic.options.indexOfFirst { it.id=="correct" }+1
        assertEquals("정답은 ${index}번이에요. 검증한 해설",first.topic.replyByChoice["wrong"])
        c.recordPresented(first)
        assertNotEquals(first.topic.id,c.select(0.5,0.5,true,"quiz")?.topic?.id)
    }

}
