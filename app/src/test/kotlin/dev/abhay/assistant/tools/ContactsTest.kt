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
    )

    private fun names(query: String, limit: Int = 3) = ContactMatch.rank(query, book, limit).map { it.name }

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
        assertThat(names("the")).isEmpty()
    }

    @Test
    fun soundsDropWhatTypingByEarChanges() {
        assertThat(ContactMatch.sound("shreyansh")).isEqualTo(ContactMatch.sound("sreyans"))
        assertThat(ContactMatch.sound("bhavna")).isEqualTo(ContactMatch.sound("bavna"))
        assertThat(ContactMatch.sound("deepak")).isEqualTo(ContactMatch.sound("dipak"))
    }
}
