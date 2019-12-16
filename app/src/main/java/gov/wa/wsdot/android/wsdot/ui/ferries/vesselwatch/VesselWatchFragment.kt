package gov.wa.wsdot.android.wsdot.ui.ferries.vesselwatch

import android.Manifest
import android.annotation.SuppressLint
import android.content.res.Resources
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.android.support.DaggerFragment
import gov.wa.wsdot.android.wsdot.NavGraphDirections
import gov.wa.wsdot.android.wsdot.R
import gov.wa.wsdot.android.wsdot.databinding.VesselWatchBinding
import gov.wa.wsdot.android.wsdot.db.ferries.Vessel
import gov.wa.wsdot.android.wsdot.db.traffic.Camera
import gov.wa.wsdot.android.wsdot.di.Injectable
import gov.wa.wsdot.android.wsdot.ui.MainActivity
import gov.wa.wsdot.android.wsdot.ui.cameras.CameraViewModel
import gov.wa.wsdot.android.wsdot.util.NightModeConfig
import gov.wa.wsdot.android.wsdot.util.autoCleared
import gov.wa.wsdot.android.wsdot.util.getDouble
import gov.wa.wsdot.android.wsdot.util.network.Status
import gov.wa.wsdot.android.wsdot.util.putDouble
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnShowRationale
import permissions.dispatcher.PermissionRequest
import permissions.dispatcher.RuntimePermissions
import javax.inject.Inject

@RuntimePermissions
class VesselWatchFragment: DaggerFragment(), Injectable, OnMapReadyCallback, GoogleMap.OnMarkerClickListener {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory
    lateinit var vesselViewModel: VesselWatchViewModel
    lateinit var cameraViewModel: CameraViewModel

    var binding by autoCleared<VesselWatchBinding>()

    private var mMap: GoogleMap? = null

    private lateinit var mapFragment: SupportMapFragment

    private val vesselMarkers = HashMap<Marker, Vessel>()
    private val cameraMarkers = HashMap<Marker, Camera>()

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var showCameras: Boolean = true
    private var selectedCameraMarker: Marker? = null

    private lateinit var vesselUpdateHandler: Handler
    private val vesselUpdateTask = object: Runnable {
        override fun run() {
            vesselViewModel.refresh()
            vesselUpdateHandler.postDelayed(this, 180000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vesselUpdateHandler = Handler(Looper.getMainLooper())
        (activity as MainActivity).setScreenName(this::class.java.simpleName)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        // set up view models
        vesselViewModel = ViewModelProviders.of(this, viewModelFactory)
            .get(VesselWatchViewModel::class.java)

        // create the data binding
        val dataBinding = DataBindingUtil.inflate<VesselWatchBinding>(
            inflater,
            R.layout.vessel_watch,
            container,
            false)

        mapFragment = childFragmentManager.findFragmentById(R.id.google_map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val settings = PreferenceManager.getDefaultSharedPreferences(activity)
        showCameras = settings.getBoolean(getString(R.string.user_preference_vessel_watch_cameras), true)

        vesselViewModel.setShowCameras(showCameras)

        dataBinding.vesselViewModel = vesselViewModel

        dataBinding.lifecycleOwner = this

        binding = dataBinding

        initBottomSheets()

        return dataBinding.root
    }


    override fun onMapReady(map: GoogleMap?) {

        mMap = map as GoogleMap

        context?.let {
            if (NightModeConfig.nightModeOn(it)) {
                try {
                    // Customise the styling of the base map using a JSON object defined
                    // in a raw resource file.
                    mMap?.setMapStyle(
                        MapStyleOptions.loadRawResourceStyle(
                            it, R.raw.map_night_style))

                } catch (e: Resources.NotFoundException) {
                    Log.e("debug", "Can't find style. Error: ", e)
                }

            } else {
                mMap?.setMapStyle(null)
            }
        }

        enableMyLocationWithPermissionCheck()

        val settings = PreferenceManager.getDefaultSharedPreferences(activity)

        val latitude = settings.getDouble(getString(R.string.user_preference_vessel_watch_latitude), 47.583571)
        val longitude = settings.getDouble(getString(R.string.user_preference_vessel_watch_longitude), -122.473468)
        val zoom = settings.getFloat(getString(R.string.user_preference_vessel_watch_zoom), 10f)

        val startLocation = LatLng(latitude, longitude)

        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, zoom))
        mMap?.setOnMarkerClickListener(this)

        vesselViewModel.vessels.observe(viewLifecycleOwner, Observer { vessels ->
            if (vessels.data != null) {

                // loop over vessel vesselMarkers, removing any old ones from google map and vesselMarkers hash map
                with(vesselMarkers.iterator()) {
                    forEach {
                            it.key.remove()
                            remove()
                    }
                }

                for (vessel in vessels.data) {

                    if (vessel.inService) {
                        val stopped = vessel.speed < 0.5
                        val marker = mMap?.addMarker(MarkerOptions()
                            .position(LatLng(vessel.latitude, vessel.longitude))
                            .rotation(if (stopped) 0f else vessel.heading.toFloat())
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.ferry_0)))
                        marker?.let {
                            vesselMarkers[it] = vessel
                        }
                    }
                }
            }

            if (vessels.status == Status.ERROR) {
                Toast.makeText(
                    context,
                    getString(R.string.loading_error_message),
                    Toast.LENGTH_SHORT
                ).show()
            }

        })


        vesselViewModel.cameras.observe(viewLifecycleOwner, Observer { cameras ->
            if (cameras.data != null) {

                with(cameraMarkers.iterator()) {
                    forEach {
                        it.key.remove()
                        remove()
                    }
                }

                for (camera in cameras.data) {
                    val marker = mMap?.addMarker(MarkerOptions()
                        .position(LatLng(camera.latitude, camera.longitude))
                        .visible(showCameras)
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.camera)))
                    marker?.let {
                        cameraMarkers[it] = camera
                    }

                }
            }
        })

        binding.cameraVisibilityFab.setOnClickListener {
            val editor = PreferenceManager.getDefaultSharedPreferences(activity).edit()
            editor.putBoolean(getString(R.string.user_preference_vessel_watch_cameras), !showCameras)
            editor.apply()
            showCameras = !showCameras
            vesselViewModel.setShowCameras(showCameras)

            // loop over camera markers, setting viability
            with(cameraMarkers.iterator()) {
                forEach {
                    it.key.isVisible = showCameras
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vesselUpdateHandler.post(vesselUpdateTask)
    }

    override fun onPause() {

        mMap?.let { map ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putDouble(getString(R.string.user_preference_vessel_watch_latitude), map.projection.visibleRegion.latLngBounds.center.latitude)
            editor.putDouble(getString(R.string.user_preference_vessel_watch_longitude), map.projection.visibleRegion.latLngBounds.center.longitude)
            editor.putFloat(getString(R.string.user_preference_vessel_watch_zoom), map.cameraPosition.zoom)
            editor.apply()
        }

        vesselUpdateHandler.removeCallbacks(vesselUpdateTask)
        super.onPause()
    }

    override fun onMarkerClick(marker: Marker): Boolean {

        vesselMarkers[marker]?.let {
            val action = NavGraphDirections.actionGlobalNavVesselDetailsFragment(it.vesselId, it.vesselName)
            findNavController().navigate(action)
            return true
        }

        cameraMarkers[marker]?.let { camera ->

            selectedCameraMarker?.remove()
            val icon = BitmapDescriptorFactory.fromResource(R.drawable.camera_selected)

            selectedCameraMarker = mMap?.addMarker(MarkerOptions()
                .zIndex(100f)
                .position(LatLng(camera .latitude, camera .longitude))
                .visible(true)
                .icon(icon))

            cameraViewModel.setCameraQuery(camera.cameraId)

            binding.favoriteButton.setOnClickListener {
                cameraViewModel.updateFavorite(camera .cameraId)
            }

            BottomSheetBehavior.from(binding.cameraBottomSheet).state =
                BottomSheetBehavior.STATE_EXPANDED

            return true
        }

        return true
    }

    // functions to handle bottom sheet logic
    private fun initBottomSheets() {

        // Camera Bottom Sheet
        cameraViewModel = ViewModelProviders.of(this, viewModelFactory)
            .get(CameraViewModel::class.java)
        cameraViewModel.setCameraQuery(-1)

        binding.cameraViewModel = cameraViewModel

        val behavior = BottomSheetBehavior.from(binding.cameraBottomSheet)

        val bottomSheetBehaviorCallback =
            object : BottomSheetBehavior.BottomSheetCallback() {

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        selectedCameraMarker?.remove()
                    }
                }
            }
        behavior.addBottomSheetCallback(bottomSheetBehaviorCallback)

        binding.favoriteButton.setOnClickListener(null)
        binding.favoriteButton.setOnClickListener {
            cameraViewModel.updateFavorite(-1)
        }

        binding.closeButton.setOnClickListener {
            BottomSheetBehavior.from(binding.cameraBottomSheet).state = BottomSheetBehavior.STATE_COLLAPSED
        }

    }


    // Location Permission
    @SuppressLint("MissingPermission")
    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun enableMyLocation() {
        context?.let { context ->
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location : Location? ->
                    location?.let {
                        mMap?.isMyLocationEnabled = true
                    }
                }
        }
    }

    @OnShowRationale(Manifest.permission.ACCESS_FINE_LOCATION)
    fun showRationaleForLocation(request: PermissionRequest) {
        showRationaleDialog(R.string.permission_map_location_rationale, request)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun showRationaleDialog(rationMessage: Int, permRequest: PermissionRequest) {
        context?.let {
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Location Permission")
            builder.setMessage(rationMessage)
            builder.setCancelable(false)
            builder.setPositiveButton("next") { _, _ -> permRequest.proceed()}
            val dialog: AlertDialog = builder.create()
            dialog.show()
        }
    }

}