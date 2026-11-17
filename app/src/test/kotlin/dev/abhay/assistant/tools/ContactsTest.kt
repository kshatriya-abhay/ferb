package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactsTest {
    private val book = listOf(
        Contact("Priya Sharma", "+91 98765 43210", "Mobile"),
        Contact("Priya Singh", "+91 99887 76655", "Mobile", starred = true),
        Contact("Shreyansh Jain", "+91 91234 56789", "Mobile"),
        Contact("Rohit Thakur", "+91 90000 11111", "Work"),
        Contact("माँ", "+91 98111 22222", "Mobile", latin = "ma"),
        Contact("Papa", "+91 98333 44444", "Home"),
        Contact("Vikram", "+91 97777 88888"),
        Contact("Kriyansh Verma", "+91 96666 77777"),
    )

    private fun names(query: String, limit: Int = 3) = ContactMatch.rank(query, book, limit).map { it.name }

    @Test
    fun theNameSaidBeatsTheRelationWord() {
        // Review P2-84: one relation word made every contact with one in its name a perfect match.
        val people = listOf(Contact("Rahul Verma", "+1 555 0101"), Contact("Ankit Bhaiya", "+1 555 0102"),
            Contact("Priya Didi", "+1 555 0103"), Contact("Neha Didi", "+1 555 0104"))
        assertThat(ContactMatch.rank("Rahul bhaiya", people, 1).map { it.name }).containsExactly("Rahul Verma")
        assertThat(ContactMatch.rank("Priya didi", people, 1).map { it.name }).containsExactly("Priya Didi")
        // A relation alone still finds the one saved that way.
        assertThat(ContactMatch.rank("bhaiya", people, 1).map { it.name }).containsExactly("Ankit Bhaiya")
    }

    @Test
    fun aContactsNumberIsTheDefaultThenThePrimaryThenAMobileInAStableOrder() {
        // Review P2-85: with no default, the number was whichever row the provider gave first.
        fun row(id: Long, number: String, mobile: Boolean = true, primary: Boolean = false, default: Boolean = false) =
            ContactBook.Row(id, Contact("Asha", number), mobile, primary, default)
        assertThat(ContactBook.oneNumber(listOf(row(9, "new"), row(3, "old"))).number).isEqualTo("old")
        assertThat(ContactBook.oneNumber(listOf(row(3, "old"), row(9, "new", primary = true))).number).isEqualTo("new")
        assertThat(ContactBook.oneNumber(listOf(row(3, "work", mobile = false), row(9, "set", default = true))).number).isEqualTo("set")
    }

    @Test
    fun aFirstNameFindsEveryoneWithIt() {
        // Both Priyas, the starred one first.
        assertThat(names("Priya")).containsExactly("Priya Singh", "Priya Sharma").inOrder()
        assertThat(names("priya sharma").first()).isEqualTo("Priya Sharma")
    }

    @Test
    fun namesTypedByEarStillMatch() {
        assertThat(names("Shriyans").first()).isEqualTo("Shreyansh Jain")
        assertThat(names("shrey ansh").first()).isEqualTo("Shreyansh Jain")
        assertThat(names("Vickram").first()).isEqualTo("Vikram")
        assertThat(names("Rohit Thakkur").first()).isEqualTo("Rohit Thakur")
    }

    @Test
    fun relationsFindTheContactSavedThatWay() {
        assertThat(names("mum")).containsExactly("माँ")
        assertThat(names("my mom")).containsExactly("माँ")
        assertThat(names("dad")).containsExactly("Papa")
        assertThat(names("phone dad")).containsExactly("Papa")
    }

    @Test
    fun strangersMatchNoOne() {
        assertThat(names("Zubair")).isEmpty()
        // "shriansh" (Shreyansh, as heard on the audio bench) sounds like Kriyansh but for its first sound.
        assertThat(names("shriansh")).containsExactly("Shreyansh Jain")
        assertThat(ContactMatch.rank("shriansh", book.filter { it.name != "Shreyansh Jain" })).isEmpty()
        assertThat(names("the")).isEmpty()
    }

    @Test
    fun soundsDropWhatTypingByEarChanges() {
        assertThat(ContactMatch.sound("shreyansh")).isEqualTo(ContactMatch.sound("sreyans"))
        assertThat(ContactMatch.sound("bhavna")).isEqualTo(ContactMatch.sound("bavna"))
        assertThat(ContactMatch.sound("deepak")).isEqualTo(ContactMatch.sound("dipak"))
    }
}
