package com.friday.ai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for ChatMessageDao using in-memory Room database.
 * Validates CRUD operations on the chat_messages table.
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageDaoTest {

    private lateinit var database: FridayDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FridayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndReadMessage() = runTest {
        val message = ChatMessageEntity(
            id = "msg-1",
            content = "Hello Friday!",
            role = "USER",
            timestamp = 1000L
        )

        dao.insert(message)
        val messages = dao.observeAll().first()

        assertEquals(1, messages.size)
        assertEquals("Hello Friday!", messages[0].content)
        assertEquals("USER", messages[0].role)
    }

    @Test
    fun messagesOrderedByTimestamp() = runTest {
        dao.insert(ChatMessageEntity("2", "Second", "USER", 2000L))
        dao.insert(ChatMessageEntity("1", "First", "USER", 1000L))
        dao.insert(ChatMessageEntity("3", "Third", "ASSISTANT", 3000L))

        val messages = dao.observeAll().first()

        assertEquals("First", messages[0].content)
        assertEquals("Second", messages[1].content)
        assertEquals("Third", messages[2].content)
    }

    @Test
    fun deleteAllClearsTable() = runTest {
        dao.insert(ChatMessageEntity("1", "Test", "USER", 1000L))
        dao.insert(ChatMessageEntity("2", "Test2", "ASSISTANT", 2000L))

        dao.deleteAll()
        val messages = dao.observeAll().first()

        assertTrue(messages.isEmpty())
    }

    @Test
    fun countReturnsCorrectNumber() = runTest {
        assertEquals(0, dao.count())

        dao.insert(ChatMessageEntity("1", "A", "USER", 1000L))
        dao.insert(ChatMessageEntity("2", "B", "ASSISTANT", 2000L))

        assertEquals(2, dao.count())
    }

    @Test
    fun insertWithSameIdReplacesMessage() = runTest {
        dao.insert(ChatMessageEntity("1", "Original", "USER", 1000L))
        dao.insert(ChatMessageEntity("1", "Updated", "USER", 1000L))

        val messages = dao.observeAll().first()

        assertEquals(1, messages.size)
        assertEquals("Updated", messages[0].content)
    }
}
