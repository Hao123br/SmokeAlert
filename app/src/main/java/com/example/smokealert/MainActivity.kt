package com.example.smokealert

import android.os.Bundle
import android.util.Log
import android.widget.RadioButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import java.security.KeyManagementException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException

class SensorData(var alarm: Boolean = false, var index: Int = 0)

class CommandViewModel() : ViewModel() {
    private val mutableSensorData = MutableLiveData<MutableList<SensorData>>()
    val sensorData: LiveData<MutableList<SensorData>> get() = mutableSensorData
    lateinit var socket: Socket
    lateinit var mqttClient: MqttAndroidClient

    init {
        val mlist = mutableListOf<SensorData>()
        for (i in 0..4) {
            mlist.add(SensorData(false, i))
        }
        mutableSensorData.value = mlist
    }

    fun subscribe(topic: String, qos: Int = 1){
        try{
            mqttClient.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(MainActivity.TAG, "Subscribed to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(MainActivity.TAG, "Failed to subscribe $topic")
                }
            })
        }   catch (e: MqttException){
            e.printStackTrace()
        }
    }

    fun unsubscribe(topic: String){
        try {
            mqttClient.unsubscribe(topic, null, object : IMqttActionListener{
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(MainActivity.TAG, "Unsubscribed to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(MainActivity.TAG, "Failed to unsubscribe $topic")
                }
            })
        }   catch (e: MqttException){
            e.printStackTrace()
        }
    }

    fun publish(topic: String, msg: String, qos: Int = 1, retained: Boolean = false) {
        try {
            val message = MqttMessage()
            message.payload = msg.toByteArray()
            message.qos = qos
            message.isRetained = retained
            mqttClient.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(MainActivity.TAG, "$msg published to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(MainActivity.TAG, "Failed to publish $msg to $topic")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            mqttClient.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(MainActivity.TAG, "Disconnected")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(MainActivity.TAG, "Failed to disconnect")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    private fun parseSensorData(data: String): SensorData {
        val split = data.removeSuffix("\n").split(" ", "\n")
        val measurements = HashMap<String, String>()

        for (s in split) {
            val pair = s.split(":")
            measurements[pair[0]] = pair[1]
        }

        val state = measurements["S"]!!.toInt()
        var bstate = false
        if (state == 1)
            bstate = true
        val index = measurements["I"]!!.toInt()
        return SensorData(bstate, index)
    }

    fun updateSensorData(data: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = mutableSensorData.value!!
            val pdata = parseSensorData(data)
            list[pdata.index] = pdata
            mutableSensorData.value = list
        }
    }

    fun connectController(mqttOptions: MqttConnectOptions) {
        viewModelScope.launch(Dispatchers.IO) {
            var data: String?

            try {
                socket = Socket()
                socket.connect(InetSocketAddress("192.168.1.109", 42032), 500)

                val socketInput = BufferedReader(InputStreamReader(socket.getInputStream()))

                socket.getOutputStream().write("m".toByteArray())
                while (true) {
                    try {
                        data = socketInput.readLine()
                        if (data == null)
                            break
                    } catch (e: Exception) {
                        break
                    }

                    when {
                        data.startsWith("S:") -> updateSensorData(data)
                    }
                }
                return@launch
            } catch (e: IOException) {
                Log.i("controle de bomba: connectController",
                    "No controller found in local network. trying mqtt…")
            }

            Log.i("controle de bomba: connectController",
                "Connecting to the broker at ${mqttClient.serverURI}")
            mqttClient.connect(mqttOptions)
        }
    }

    fun disconnectController() {
        viewModelScope.launch(Dispatchers.IO) {
            if (socket.isConnected) {
                socket.getOutputStream().write(" ".toByteArray())
                socket.shutdownOutput()
                socket.close()
            } else if (mqttClient.isConnected) {
                publish("commands", " ", 2)
                println("Disconnecting from the broker at ${mqttClient.serverURI}")
                disconnect()
            }
        }
    }

    fun setPumpStatus(pump: String, status: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val action = when (status) {
                true -> "enable"
                false -> "disable"
            }

            if (socket.isConnected) {
                val socketOutput = socket.getOutputStream()
                socketOutput.write("$action $pump pump".toByteArray())
            } else if(mqttClient.isConnected){
                publish("commands", "$action $pump pump", 2, false)
            }
        }
    }
}

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "AndroidMqttClient"
    }

    private val commandViewModel: CommandViewModel by viewModels()
    private lateinit var mqttClient: MqttAndroidClient
    private val mqttOptions = MqttConnectOptions()
    private val indicatorsIds = listOf(R.id.radioButton_1, R.id.radioButton_4, R.id.radioButton_2,
                                        R.id.radioButton_3, R.id.radioButton_5)
    private val indicators = mutableListOf<RadioButton>()

    // Configuration
    private val CERTFILE_NAME = "ca.crt"
    private val KEYSTORE_NAME = "app.p12"

    private val BROKER_URL = "ssl://betahalo.ddns.net:42083"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        for (i in 0..4) {
            val button = findViewById<RadioButton>(indicatorsIds[i])
            indicators.add(button)
        }

        commandViewModel.sensorData.observe(this) { data ->
            for (sensor in data) {
                indicators[sensor.index].isChecked = sensor.alarm
                indicators[sensor.index].invalidate()
            }
        }

        val clientId = MqttClient.generateClientId()
        val viewModel = ViewModelProvider(this)[CommandViewModel::class.java]
        mqttClient = MqttAndroidClient(this.applicationContext, BROKER_URL, clientId)

        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d(TAG, "Receber mensagem: ${message.toString()} do tópico: $topic")
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    val data = message.toString().removeSuffix("\n") //remove linebreak
                    when {
                        data.contains("S:") -> viewModel.updateSensorData(data)
                    }
                }
            }

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "Conexão completa")
                commandViewModel.subscribe("esp32",2)
                commandViewModel.publish("commands","m",2,false)
            }

            override fun connectionLost(cause: Throwable?) {
                Log.d(TAG, "Conexão perdida ${cause.toString()}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {

            }
        })

        mqttOptions.isCleanSession = true

        val socketFactoryOptions: SocketFactory.SocketFactoryOptions =
            SocketFactory.SocketFactoryOptions()
        try {
            socketFactoryOptions.withCaInputStream(
                assets.open(CERTFILE_NAME)
            )
            socketFactoryOptions.withClientP12InputStream(
                assets.open(KEYSTORE_NAME)
            )
            socketFactoryOptions.withClientP12Password("1234")
            mqttOptions.socketFactory = SocketFactory(socketFactoryOptions)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        } catch (e: KeyStoreException) {
            e.printStackTrace()
        } catch (e: CertificateException) {
            e.printStackTrace()
        } catch (e: KeyManagementException) {
            e.printStackTrace()
        } catch (e: UnrecoverableKeyException) {
            e.printStackTrace()
        }

        viewModel.mqttClient = mqttClient

//        Thread.setDefaultUncaughtExceptionHandler { _, e ->
//            // Get the stack trace.
//            val sw = StringWriter()
//            val pw = PrintWriter(sw)
//            e.printStackTrace(pw)
//
//            // Add it to the clip board and close the app
//            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
//            val clip = ClipData.newPlainText("Stack trace", sw.toString())
//            clipboard.setPrimaryClip(clip)
//            val crashFragment = CrashFragment()
//            crashFragment.show(supportFragmentManager, "crash")
//        }
    }

    override fun onResume() {
        super.onResume()
        commandViewModel.connectController(mqttOptions)
    }

    override fun onPause() {
        super.onPause()
        commandViewModel.disconnectController()
    }
}