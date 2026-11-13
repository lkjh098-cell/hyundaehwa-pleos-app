package ai.hyundaehwa.core

/** Authored destination choices from the v46 demonstration, not assertions about availability. */
internal data class DestinationChoice(val id: String, val prompt: String, val first: String, val second: String, val firstKeywords: List<String>, val secondKeywords: List<String>)
internal object DestinationTemplates {
    val choices = mapOf(
        "beach" to listOf(
            DestinationChoice("beach-sand-or-view", "이용 가능한 곳에서 무엇부터 즐길까요?", "모래사장을 천천히 걸으며 바다를 가까이 봐요", "편하게 앉아서 바다를 바라보며 쉬어요", listOf("모래사장", "걸으며"), listOf("앉아서", "쉬어요")),
            DestinationChoice("beach-photos-or-food", "바닷가에서 시간을 먼저 쓴다면?", "바다를 배경으로 사진부터 남겨요", "바다 구경은 나중에 하고 식사부터 해요", listOf("바다 사진", "사진부터"), listOf("식사부터", "밥")),
            DestinationChoice("beach-short-or-long", "바닷가에 머무는 시간을 정한다면?", "바다 한곳에서 오래 쉬어요", "바다는 짧게 보고 주변 거리를 더 둘러봐요", listOf("오래", "한곳"), listOf("짧게", "거리")),
            DestinationChoice("beach-snack-or-picnic", "도착 전에 먹을 것을 준비한다면?", "정해진 장소에서 먹을 간단한 간식을 챙겨요", "먹을 것은 현지에서 정하고 가볍게 가요", listOf("간식", "챙겨"), listOf("현지", "가볍게")),
            DestinationChoice("beach-morning-or-evening", "나중에 여유가 생기면 바다를 언제 다시 볼까요?", "푹 쉰 다음 날 아침에 다시 보러 가요", "저녁 시간에 다시 보러 가요", listOf("아침", "다음 날"), listOf("저녁")),
            DestinationChoice("beach-record-or-talk", "바다 앞에서 한 시간을 보낸다면?", "휴대전화를 넣고 편하게 이야기를 나눠요", "서로 사진을 찍어 주며 시간을 보내요", listOf("이야기", "휴대전화를 넣고"), listOf("사진", "찍어")),
        ),
        "hanok" to listOf(
            DestinationChoice("hanok-walk-or-photo", "골목을 둘러볼 수 있다면 무엇부터 할까요?", "편한 속도로 골목을 한 바퀴 돌아요", "마음에 드는 배경을 골라 사진부터 찍어요", listOf("골목", "한 바퀴"), listOf("사진", "배경")),
            DestinationChoice("hanok-outfit-or-comfort", "한옥을 배경으로 하루를 보낸다면?", "사진에 어울리는 옷을 준비해요", "오래 걸어도 편한 옷과 신발을 골라요", listOf("사진 옷", "준비"), listOf("편한 옷", "신발")),
            DestinationChoice("hanok-food-or-walk", "도착하자마자 시간을 먼저 쓴다면?", "가볍게 식사한 뒤 골목을 둘러봐요", "골목부터 구경하고 식사는 나중에 해요", listOf("식사", "먼저 먹고"), listOf("골목부터", "나중에")),
            DestinationChoice("hanok-route-or-roam", "함께 골목을 돌아보는 방법은?", "돌아볼 길을 정하고 차례로 걸어요", "이용 가능한 골목을 보고 그때그때 골라요", listOf("길을 정하고", "차례"), listOf("그때그때", "골라")),
            DestinationChoice("hanok-small-or-none", "구경을 마친 뒤 하나를 남긴다면?", "마음에 드는 작은 물건을 하나 골라요", "물건은 사지 않고 사진만 남겨요", listOf("작은 물건", "골라"), listOf("사진만", "사지")),
            DestinationChoice("hanok-one-or-many", "사진을 남기는 시간을 고른다면?", "한 배경에서 마음에 드는 사진을 충분히 찍어요", "여러 골목에서 한 장씩만 남겨요", listOf("한 배경", "충분히"), listOf("여러 골목", "한 장씩")),
        ),
        "mountain" to listOf(
            DestinationChoice("mountain-short-or-rest", "이용 가능한 안전한 길을 확인한 뒤 무엇을 할까요?", "체력에 맞는 짧은 길을 걸어요", "걷기보다 편한 곳에서 풍경을 보며 쉬어요", listOf("짧은 길", "걸어요"), listOf("쉬어요", "풍경")),
            DestinationChoice("mountain-packed-or-light", "도착 전에 준비할 것을 고른다면?", "정해진 휴식 장소에서 먹을 간식을 챙겨요", "짐은 가볍게 하고 식사는 나중에 해요", listOf("간식", "챙겨"), listOf("가볍게", "나중에")),
            DestinationChoice("mountain-photo-or-time", "산에서 쉬는 시간을 보낸다면?", "안전한 자리에서 풍경 사진을 남겨요", "사진 없이 조용히 쉬는 데 집중해요", listOf("사진", "남겨"), listOf("사진 없이", "조용히")),
            DestinationChoice("mountain-one-or-stop", "몸 상태에 맞춰 쉬는 방식을 정한다면?", "짧게 걸은 뒤 오래 쉬어요", "여러 번 짧게 쉬며 무리 없이 둘러봐요", listOf("오래 쉬어요", "짧게 걸은"), listOf("여러 번", "무리 없이")),
            DestinationChoice("mountain-after-or-before", "도착해서 식사를 언제 할까요?", "식사를 마치고 충분히 쉰 뒤 움직여요", "가볍게 둘러본 뒤 식사해요", listOf("식사 먼저", "충분히"), listOf("둘러본 뒤", "나중에")),
            DestinationChoice("mountain-plan-or-rest", "가능한 활동이 생각보다 힘들게 느껴지면?", "일정을 줄이고 편히 쉬어요", "더 쉬운 활동을 함께 찾아봐요", listOf("쉬어요", "줄이고"), listOf("쉬운 활동", "찾아")),
        ),
        "city" to listOf(
            DestinationChoice("city-street-or-meal", "도심에서 무엇부터 하고 싶으세요?", "주변 거리를 천천히 둘러봐요", "둘러보기 전에 식사부터 해요", listOf("거리", "둘러봐"), listOf("식사부터", "밥")),
            DestinationChoice("city-shopping-or-look", "가게를 구경할 여유가 있다면?", "살 물건 하나를 정해서 찾아봐요", "정해 둔 물건 없이 구경만 해요", listOf("살 물건", "정해서"), listOf("구경만", "없이")),
            DestinationChoice("city-route-or-one", "도심에서 두 시간을 보낸다면?", "여러 거리를 짧게 돌아봐요", "마음에 든 한곳에 오래 머물러요", listOf("여러 거리", "짧게"), listOf("한곳", "오래")),
            DestinationChoice("city-photo-or-food", "둘 중 하나에 시간을 더 쓴다면?", "사진이 마음에 들 때까지 찍어요", "사진은 짧게 남기고 식사를 천천히 해요", listOf("사진", "마음에 들 때"), listOf("식사", "천천히")),
            DestinationChoice("city-pick-or-vote", "도착 후 첫 장소를 정한다면?", "한 사람이 정한 장소로 함께 가요", "각자 후보를 내고 함께 골라요", listOf("한 사람", "정한"), listOf("후보", "함께 골라")),
            DestinationChoice("city-plan-or-empty", "예상보다 시간이 많이 남으면?", "미리 봐둔 다음 장소를 찾아가요", "새 일정을 넣지 않고 편하게 쉬어요", listOf("다음 장소", "미리"), listOf("새 일정 없이", "쉬어요")),
        ),
        "museum" to listOf(
            DestinationChoice("museum-meal-first", "관람이 가능한지 확인한 뒤 무엇부터 할까요?", "식사하고 여유 있게 관람을 시작해요", "관람을 먼저 하고 식사는 나중에 해요", listOf("식사하고", "먼저 먹고"), listOf("관람 먼저", "나중에")),
            DestinationChoice("museum-time", "관람 시간을 정한다면?", "한 번에 충분히 둘러보고 쉬어요", "짧게 둘러본 뒤 쉬는 시간을 길게 가져요", listOf("충분히", "한 번에"), listOf("짧게", "길게 쉬어")),
            DestinationChoice("museum-together", "둘러볼 속도가 다르다면?", "서로 속도를 맞춰 함께 움직여요", "각자 둘러보고 약속한 자리에서 만나요", listOf("함께", "속도"), listOf("각자", "만나요")),
            DestinationChoice("museum-shop", "관람을 마친 뒤 쉬는 시간을 고른다면?", "기념품을 구경할 시간을 남겨 둬요", "구경보다 먹고 쉬는 시간을 남겨 둬요", listOf("기념품", "구경"), listOf("먹고", "쉬는")),
        ),
        "park" to listOf(
            DestinationChoice("park-walk-or-sit", "공원에서 이용 가능한 공간을 확인한 뒤 무엇부터 할까요?", "안전한 산책길을 천천히 걸어요", "쉴 수 있는 자리에 앉아서 여유를 즐겨요", listOf("산책길", "걸어요"), listOf("앉아서", "여유")),
            DestinationChoice("park-picnic-or-light", "머무는 방식을 정한다면?", "허용된 자리에서 먹을 간식을 준비해요", "짐 없이 가볍게 둘러보고 식사는 나중에 해요", listOf("간식", "준비"), listOf("짐 없이", "가볍게")),
            DestinationChoice("park-photo-or-chat", "쉬는 시간을 고른다면?", "사진을 남기며 한 바퀴 둘러봐요", "사진 없이 편하게 이야기를 나눠요", listOf("사진", "한 바퀴"), listOf("이야기", "사진 없이")),
            DestinationChoice("park-stay-or-next", "시간이 남는다면 무엇을 할까요?", "공원 한곳에서 오래 쉬어요", "공원은 짧게 보고 다음 장소를 골라요", listOf("오래", "한곳"), listOf("다음 장소", "짧게")),
        ),
        "market" to listOf(
            DestinationChoice("market-sample-or-meal", "먹을 수 있는 메뉴를 확인한 뒤 고른다면?", "여러 간식을 조금씩 나눠 먹어요", "한 메뉴를 골라 든든하게 먹어요", listOf("여러 간식", "조금씩"), listOf("한 메뉴", "든든")),
            DestinationChoice("market-list-or-look", "시장에서 구경할 물건을 정한다면?", "살 것 목록을 정해서 필요한 것만 골라요", "구경하면서 마음에 드는 것이 있으면 골라요", listOf("목록", "필요한"), listOf("구경하면서", "마음에")),
            DestinationChoice("market-food-or-gift", "한 가지에만 예산을 쓴다면?", "함께 먹을 음식에 써요", "돌아가서 쓸 물건이나 선물에 써요", listOf("음식", "먹을"), listOf("물건", "선물")),
            DestinationChoice("market-full-or-short", "둘러보는 시간을 정한다면?", "시장을 충분히 둘러보고 식사를 골라요", "필요한 곳만 짧게 보고 바로 쉬어요", listOf("충분히", "식사"), listOf("짧게", "쉬어요")),
        ),
        "general" to listOf(
            DestinationChoice("destination-rest-or-start", "도착한 뒤 무엇부터 할까요?", "잠깐 쉬면서 하루를 천천히 시작해요", "쉬기 전에 가장 하고 싶던 일부터 해요", listOf("쉬면서", "천천히"), listOf("하고 싶던 일", "먼저")),
            DestinationChoice("destination-food-or-look", "첫 시간을 어떻게 쓸까요?", "식사부터 하고 주변을 둘러봐요", "주변을 먼저 보고 식사 메뉴를 정해요", listOf("식사부터", "먹고"), listOf("주변 먼저", "보고")),
            DestinationChoice("destination-one-or-share", "함께 보낼 시간을 정한다면?", "한 사람의 추천을 믿고 하루를 맡겨요", "각자 한 가지씩 골라 일정에 넣어요", listOf("한 사람", "맡겨"), listOf("각자", "한 가지씩")),
            DestinationChoice("destination-record-or-rest", "여유 시간이 생기면 무엇을 할까요?", "사진과 메모로 오늘을 남겨요", "휴대전화를 넣고 편히 쉬어요", listOf("사진", "메모"), listOf("휴대전화를 넣고", "쉬어요")),
            DestinationChoice("destination-spend-or-save", "도착 후 예산을 정한다면?", "평소 해보고 싶던 한 가지에 넉넉히 써요", "돈을 적게 쓰고 여유로운 시간을 즐겨요", listOf("넉넉히", "해보고"), listOf("적게", "여유")),
            DestinationChoice("destination-fixed-or-open", "첫 일정을 마친 뒤 어떻게 할까요?", "미리 정한 다음 일정을 이어가요", "남은 시간은 비워 두고 함께 정해요", listOf("다음 일정", "미리"), listOf("비워", "함께 정해")),
        ),
    )
}
