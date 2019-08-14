package gov.wa.wsdot.android.wsdot.api

import androidx.lifecycle.LiveData
import gov.wa.wsdot.android.wsdot.api.response.ferries.FerryScheduleResponse
import gov.wa.wsdot.android.wsdot.api.response.mountainpass.MountainPassResponse
import gov.wa.wsdot.android.wsdot.api.response.traffic.CamerasResponse
import gov.wa.wsdot.android.wsdot.api.response.traffic.HighwayAlertsResponse
import gov.wa.wsdot.android.wsdot.api.response.travelerinfo.NewsReleaseResponse
import retrofit2.http.GET

interface WebDataService {

    /**
     * @GET declares an HTTP GET request
     */
    @GET("WSFRouteSchedules.js")
    fun getFerrySchedules(): LiveData<ApiResponse<List<FerryScheduleResponse>>>

    @GET("MountainPassConditions.js")
    fun getMountainPassReports(): LiveData<ApiResponse<MountainPassResponse>>

    @GET("HighwayAlerts.js")
    fun getHighwayAlerts(): LiveData<ApiResponse<HighwayAlertsResponse>>

    @GET("Cameras.js")
    fun getCameras(): LiveData<ApiResponse<CamerasResponse>>

    @GET("News.js")
    fun getNewsItems(): LiveData<ApiResponse<NewsReleaseResponse>>

}