package com.lily.ai

import android.content.Context
import android.provider.ContactsContract

object ContactsHelper {

    fun getAllContacts(context: Context): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return contacts

            cursor.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val phone = it.getString(phoneIdx)?.replace("\\s".toRegex(), "") ?: continue
                    val key = "$name|$phone"
                    if (!seen.contains(key)) {
                        seen.add(key)
                        contacts.add(Pair(name, phone))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return contacts
    }

    fun findByName(contacts: List<Pair<String, String>>, query: String): Pair<String, String>? {
        val q = query.lowercase().trim()
        // Exact match first
        contacts.firstOrNull { it.first.lowercase() == q }?.let { return it }
        // Contains match
        contacts.firstOrNull { it.first.lowercase().contains(q) }?.let { return it }
        // Word match
        val words = q.split(" ")
        contacts.firstOrNull { contact ->
            words.any { word -> contact.first.lowercase().contains(word) }
        }?.let { return it }
        return null
    }
}
