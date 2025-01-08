package com.example.todolist

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.semantics.text
import com.example.todolist.databinding.ActivityAddTodoBinding
import com.example.todolist.models.Todo
import com.example.todolist.signin.SignInActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import java.io.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddTodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddTodoBinding
    private lateinit var todo: Todo
    private lateinit var oldTodo: Todo
    private var isUpdate = false
    private var selectedDate: java.util.Calendar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddTodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleIntentData()
        setupUIComponents()

        binding.imgCheck.setOnClickListener { saveTodo() }
        binding.imgDelete.setOnClickListener { deleteTodo() }
        binding.imgBackArrow.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun saveTodo() {
        val title = binding.etTitle.text.toString()
        val todoDescription = binding.etNote.text.toString()

        if (title.isNotBlank() && todoDescription.isNotBlank()) {
            val formatter = SimpleDateFormat("EEE, d MMM yyyy HH:mm a", Locale.getDefault())
            todo = if (isUpdate) {
                Todo(oldTodo.id, title, todoDescription, formatter.format(java.util.Date()))
            } else {
                Todo(null, title, todoDescription, formatter.format(java.util.Date()))
            }

            if (selectedDate != null) {
                val event = createGoogleCalendarEvent(title, todoDescription)

                // Récupérer l'email des SharedPreferences
                val sharedPref = getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
                val email = sharedPref.getString("user_email", null)

                if (email != null) {
                    // Appeler insertEventToGoogleCalendar() avec l'email
                    insertEventToGoogleCalendar(event, email)
                } else {
                    // Gérer le cas où l'email n'est pas disponible
                    Log.e("AddTodoActivity", "Erreur : email du compte google non disponible")
                    Toast.makeText(this, "Erreur : email du compte google non disponible", Toast.LENGTH_SHORT).show()
                }
            }

            setResultAndFinish()
        } else {
            Toast.makeText(this, "Veuillez saisir des données", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleIntentData() {
        oldTodo = intent.getSerializableExtra("current_todo") as? Todo ?: Todo(null, "", "", "")
        isUpdate = oldTodo.id != null
        binding.etTitle.setText(oldTodo.title)
        binding.etNote.setText(oldTodo.note)
        binding.imgDelete.visibility = if (isUpdate) View.VISIBLE else View.INVISIBLE
    }

    private fun setupUIComponents() {
        binding.imgCalendar.setOnClickListener {
            val intent = Intent(this, DatePickerActivity::class.java)
            datePickerLauncher.launch(intent)
        }
    }

    private val datePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getLongExtra("selected_date", -1)?.let { timestamp ->
                selectedDate = java.util.Calendar.getInstance().apply {
                    timeInMillis = timestamp
                }
                val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                Toast.makeText(
                    this,
                    "Date sélectionnée : ${dateFormat.format(selectedDate?.time)}",
                    Toast.LENGTH_SHORT
                ).show()

                addTodoToCalendar()
            }
        }
    }

    private fun addTodoToCalendar() {
        val selectedDate = this.selectedDate
        if (selectedDate == null) {
            return
        }
        val title = binding.etTitle.text.toString()
        val todoDescription = binding.etNote.text.toString()

        val date = selectedDate.time

        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val formattedDate = dateFormatter.format(date)

        if (title.isNotBlank() && todoDescription.isNotBlank()) {
            todo = Todo(
                id = 0,
                title = title,
                note = todoDescription,
                date = formattedDate
            )

            val sharedPref = getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
            val email = sharedPref.getString("user_email", null)

            if (email != null) {
                val event = createGoogleCalendarEvent(title, todoDescription)
                insertEventToGoogleCalendar(event, email)
//                setResultAndFinish()
            } else {
                Log.e("AddTodoActivity", "Erreur : email du compte Google non disponible")
                Toast.makeText(this, "Erreur : email du compte Google non disponible", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Veuillez saisir des données", Toast.LENGTH_LONG).show()
        }
    }


    private fun createGoogleCalendarEvent(title: String, description: String): Event {
        return Event().apply {
            summary = title
            setDescription(description)

            selectedDate?.time?.let { date ->
                Log.d("AddTodoActivity", "Création événement pour la date: ${date}")
                val startDateTime = EventDateTime().apply {
                    setDate(com.google.api.client.util.DateTime(date))
                }
                setStart(startDateTime)
                setEnd(startDateTime)
            } ?: run {
                Log.e("AddTodoActivity", "Erreur : date sélectionnée non disponible")
                throw IllegalStateException("Date sélectionnée non disponible")
            }
        }
    }


    private fun insertEventToGoogleCalendar(event: Event, email: String) {
        try {
            // Essayer d'abord de récupérer le compte depuis GoogleSignIn
            var account = GoogleSignIn.getLastSignedInAccount(this)

            if (account == null) {
                // Si null, essayer de récupérer depuis SignInActivity
                account = SignInActivity.getLastSignedInAccount()
            }

            if (account == null) {
                Log.e("AddTodoActivity", "Compte Google non disponible - tentative de reconnexion")
                // Rediriger vers SignInActivity
                val intent = Intent(this, SignInActivity::class.java)
                startActivity(intent)
                return
            }

            Log.d("AddTodoActivity", "Tentative d'ajout avec email: ${account.email}")

            val credential = GoogleAccountCredential.usingOAuth2(
                this,
                listOf(CalendarScopes.CALENDAR)
            ).apply {
                selectedAccount = Account(account.email!!, "com.google")
            }

            Log.d("AddTodoActivity", "Credential créé")

            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            val calendarService = Calendar.Builder(transport, jsonFactory, credential)
                .setApplicationName("My ToDo List")
                .build()

            Log.d("AddTodoActivity", "Service Calendar créé")

            // Exécuter la requête dans un thread séparé
            Thread {
                try {
                    val createdEvent = calendarService.events().insert("primary", event).execute()
                    runOnUiThread {
                        Log.d("AddTodoActivity", "Événement créé avec succès: ${createdEvent.htmlLink}")
                        Toast.makeText(this, "Tâche ajoutée au calendrier", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("AddTodoActivity", "Erreur lors de l'insertion de l'événement", e)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Erreur lors de l'ajout au calendrier : ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e("AddTodoActivity", "Erreur lors de la configuration du service Calendar", e)
            Toast.makeText(
                this,
                "Erreur de configuration : ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setResultAndFinish() {
        if (::todo.isInitialized) {
            val intent = Intent().apply {
                putExtra("todo", todo)
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        } else {
            Log.e("AddTodoActivity", "La propriété 'todo' n'a pas été initialisée avant d'appeler 'setResultAndFinish()'")
            Toast.makeText(this, "Erreur : tâche non initialisée", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteTodo() {
        val intent = Intent().apply {
            putExtra("todo", oldTodo as Serializable)
            putExtra("delete_todo", true)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
