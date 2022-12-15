package com.woory.almostthere.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.woory.almostthere.data.model.AddedUserHpModel
import com.woory.almostthere.data.model.GeoPointModel
import com.woory.almostthere.data.model.LocationSearchModel
import com.woory.almostthere.data.model.MagneticInfoModel
import com.woory.almostthere.data.model.PathModel
import com.woory.almostthere.data.model.PromiseDataModel
import com.woory.almostthere.data.model.PromiseHistoryModel
import com.woory.almostthere.data.model.PromiseModel
import com.woory.almostthere.data.model.RouteType
import com.woory.almostthere.data.model.UserLocationModel
import com.woory.almostthere.data.model.UserModel
import com.woory.almostthere.data.source.NetworkDataSource
import com.woory.almostthere.data.util.MAGNETIC_FIELD_UPDATE_TERM_SECOND
import com.woory.almostthere.network.model.AddedUserHpDocument
import com.woory.almostthere.network.model.MagneticInfoDocument
import com.woory.almostthere.network.model.PromiseDocument
import com.woory.almostthere.network.model.UserLocationDocument
import com.woory.almostthere.network.model.mapper.asDomain
import com.woory.almostthere.network.model.mapper.asModel
import com.woory.almostthere.network.model.mapper.asPromiseParticipant
import com.woory.almostthere.network.model.mapper.asUserModel
import com.woory.almostthere.network.model.mapper.extractMagnetic
import com.woory.almostthere.network.service.ODsayService
import com.woory.almostthere.network.service.TMapService
import com.woory.almostthere.network.util.InviteCodeUtil
import com.woory.almostthere.network.util.TimeConverter.asMillis
import com.woory.almostthere.network.util.TimeConverter.asTimeStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.threeten.bp.OffsetDateTime
import javax.inject.Inject

class DefaultNetworkDataSource @Inject constructor(
    private val tMapService: TMapService,
    private val oDSayService: ODsayService,
    private val fireStore: FirebaseFirestore,
    private val scope: CoroutineScope
) : NetworkDataSource {

    override suspend fun getAddressByPoint(geoPoint: GeoPointModel): Result<String> {
        return runCatching {
            tMapService.getReverseGeoCoding(
                lat = geoPoint.latitude.toString(),
                lon = geoPoint.longitude.toString()
            ).addressInfo.fullAddress
        }
    }

    override suspend fun getPublicTransitRoute(
        start: GeoPointModel,
        dest: GeoPointModel
    ): Result<PathModel> {
        return runCatching {
            val response = oDSayService.getPublicTransitRoute(
                apiKey = BuildConfig.ODSAY_API_KEY,
                sx = start.longitude,
                sy = start.latitude,
                ex = dest.longitude,
                ey = dest.latitude,
            )

            val json = JSONObject(response.string()).getJSONObject("result")
            val routeInfo = json.getJSONArray("path")
                .getJSONObject(0)
                .getJSONObject("info")

            val searchType = json.getInt("searchType")
            var time = routeInfo.getInt("totalTime")
            val distance = routeInfo.getDouble("totalDistance")

            if (searchType == 1) {
                val stationInfo = json.getJSONArray("path")
                    .getJSONObject(0)
                    .getJSONArray("subPath")

                val startStationInfo = stationInfo.getJSONObject(0)
                val ssy = startStationInfo.getDouble("startY")
                val ssx = startStationInfo.getDouble("startX")
                val stationStartTime = getPublicTransitRoute(
                    start = GeoPointModel(start.latitude, start.longitude),
                    dest = GeoPointModel(ssy, ssx)
                ).getOrThrow().time

                val endStationInfo = stationInfo.getJSONObject(stationInfo.length() - 1)
                val esy = endStationInfo.getDouble("endY")
                val esx = endStationInfo.getDouble("endX")
                val stationEndTime = getPublicTransitRoute(
                    start = GeoPointModel(dest.latitude, dest.longitude),
                    dest = GeoPointModel(esy, esx)
                ).getOrThrow().time
                time += stationStartTime + stationEndTime
            }

            PathModel(
                routeType = RouteType.PUBLIC_TRANSIT,
                time = time,
                distance = distance.toInt()
            )
        }
    }

    override suspend fun getCarRoute(
        start: GeoPointModel,
        dest: GeoPointModel
    ): Result<PathModel> {
        return runCatching {
            val response = tMapService.getCarRoute(
                startX = start.longitude,
                startY = start.latitude,
                endX = dest.longitude,
                endY = dest.latitude
            )

            val json = JSONObject(response.string()).getJSONArray("features")
                .getJSONObject(0)
                .getJSONObject("properties")

            val time = json.getInt("totalTime")
            val distance = json.getInt("totalDistance")

            PathModel(
                routeType = RouteType.CAR,
                time = time / 60,
                distance = distance
            )
        }
    }

    override suspend fun getWalkRoute(
        start: GeoPointModel,
        dest: GeoPointModel
    ): Result<PathModel> {
        return runCatching {
            val response = tMapService.getWalkRoute(
                startX = start.longitude,
                startY = start.latitude,
                endX = dest.longitude,
                endY = dest.latitude
            )

            val json = JSONObject(response.string()).getJSONArray("features")
                .getJSONObject(0)
                .getJSONObject("properties")

            val time = json.getInt("totalTime")
            val distance = json.getInt("totalDistance")

            PathModel(
                routeType = RouteType.WALK,
                time = time / 60,
                distance = distance
            )
        }
    }

    override suspend fun searchLocationByKeyword(keyword: String): Result<List<LocationSearchModel>> {
        return runCatching {
            tMapService.getSearchedLocation(
                version = 1,
                searchKeyword = keyword
            ).searchPoiInfo.pois.poi.map { it.asDomain() }
        }
    }

    override suspend fun getPromiseByCode(code: String): Result<PromiseModel> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val task = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(code)
                    .get()
                Tasks.await(task)
                val res = task.result
                    .toObject(PromiseDocument::class.java)
                    ?.asDomain()
                    ?: throw UNMATCHED_STATE_EXCEPTION
                res
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    override suspend fun getReadyUserList(code: String): Result<List<UserModel>> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val gameInfo = fireStore.collection(PROMISE_COLLECTION_NAME)
                    .document(code)
                    .get().await().toObject(PromiseDocument::class.java)

                gameInfo?.users?.filter {
                    fireStore.collection(PROMISE_COLLECTION_NAME).document(code)
                        .collection(USER_READY_COLLECTION_NAME).document(it.userId).get().await()
                        .get("ready") == "READY"
                }?.map {
                    it.asUserModel()
                } ?: listOf()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> {
                    result
                }
                else -> {
                    Result.failure(exception)
                }
            }
        }


    override suspend fun getPromiseByCodeAndListen(code: String): Flow<Result<PromiseModel>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore.collection(PROMISE_COLLECTION_NAME).document(code)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                if (value == null) {
                    return@addSnapshotListener
                }

                runCatching {
                    val result = value.toObject(PromiseDocument::class.java)
                    result?.let {
                        trySend(Result.success(it.asDomain()))
                    } ?: throw UNMATCHED_STATE_EXCEPTION
                }.onFailure {
                    trySend(Result.failure(it))
                }
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun setPromise(promiseDataModel: PromiseDataModel): Result<String> =
        withContext(scope.coroutineContext) {
            var generatedCode = ""
            val result = runCatching {
                var isDone = false
                while (isDone.not()) {
                    generatedCode = InviteCodeUtil.getRandomInviteCode()
                    val task = fireStore
                        .collection(PROMISE_COLLECTION_NAME)
                        .document(requireNotNull(generatedCode))
                        .get()
                    Tasks.await(task)
                    if (task.result.data == null) {
                        isDone = true
                    }
                }
                val promiseCollection =
                    fireStore.collection(PROMISE_COLLECTION_NAME).document(generatedCode)
                val magneticCollection = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(generatedCode)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(generatedCode)

                fireStore.runBatch { batch ->
                    batch.set(promiseCollection, promiseDataModel.asModel(generatedCode))
                    batch.set(magneticCollection, promiseDataModel.extractMagnetic(generatedCode))
                }.await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(generatedCode)
                else -> Result.failure(exception)
            }
        }

    override suspend fun getUserLocationById(id: String): Flow<Result<UserLocationModel>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore.collection(LOCATION_COLLECTION_NAME).document(id)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                if (value == null) {
                    return@addSnapshotListener
                }

                runCatching {
                    val result = value.toObject(UserLocationDocument::class.java)
                    result?.let {
                        trySend(Result.success(it.asDomain()))
                    } ?: throw UNMATCHED_STATE_EXCEPTION
                }.onFailure {
                    trySend(Result.failure(it))
                }
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun setUserLocation(userLocationModel: UserLocationModel): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val res = fireStore
                    .collection(LOCATION_COLLECTION_NAME)
                    .document(userLocationModel.id)
                    .set(userLocationModel.asModel()).await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    override suspend fun setUserHp(gameToken: String, userHpModel: AddedUserHpModel): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val res = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameToken)
                    .collection(HP_COLLECTION_NAME)
                    .document(userHpModel.userId)
                    .set(userHpModel.asModel()).await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    override suspend fun addPlayer(code: String, user: UserModel): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val res = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(code)
                    .update(USERS_KEY, FieldValue.arrayUnion(user.asPromiseParticipant()))
                    .await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    /**
     * 자기장 반지름과 중심 좌표를 가져오는 함수
     */
    override suspend fun getMagneticInfoByCodeAndListen(code: String): Flow<Result<MagneticInfoModel>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(code)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(code)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                if (value == null) {
                    return@addSnapshotListener
                }

                runCatching {
                    val result = value.toObject(MagneticInfoDocument::class.java)
                    result?.let {
                        trySend(Result.success(it.asDomain()))
                    } ?: throw UNMATCHED_STATE_EXCEPTION
                }.onFailure {
                    trySend(Result.failure(it))
                }
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun getMagneticInfoByCode(code: String): Result<MagneticInfoModel> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val task = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(code)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(code)
                    .get()
                Tasks.await(task)

                val res = task
                    .result
                    .toObject(MagneticInfoDocument::class.java)
                    ?.asDomain()
                    ?: throw UNMATCHED_STATE_EXCEPTION

                res
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    override suspend fun updateInitialMagneticRadius(
        gameCode: String,
    ): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val reference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(gameCode)

                fireStore.runTransaction { transaction ->
                    val snapshot = transaction.get(reference)
                    val initialRadius =
                        snapshot.getDouble(INITIAL_RADIUS_KEY) ?: return@runTransaction
                    val radius = snapshot.getLong(RADIUS_KEY) ?: return@runTransaction

                    if (initialRadius == 1.0) {
                        transaction.update(reference, mapOf(INITIAL_RADIUS_KEY to radius))
                    }
                }.await()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun updateMagneticRadius(gameCode: String, radius: Double): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val reference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(gameCode)

                fireStore.runTransaction { transaction ->
                    val snapshot = transaction.get(reference)
                    val serverRadius = snapshot.getLong(RADIUS_KEY) ?: return@runTransaction
                    val maxValue = maxOf(serverRadius, radius.toLong())

                    transaction.update(reference, mapOf(RADIUS_KEY to maxValue))
                }.await()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun decreaseMagneticRadius(
        gameCode: String,
        minusValue: Double
    ): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val reference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(MAGNETIC_COLLECTION_NAME)
                    .document(gameCode)

                fireStore.runTransaction { transaction ->
                    val snapShot = transaction.get(reference)
                    val serverRadius = snapShot.getLong(RADIUS_KEY) ?: return@runTransaction
                    val updateTime = snapShot.getTimestamp(TIMESTAMP_KEY) ?: return@runTransaction

                    if (isFirstAccess(updateTime)) {
                        transaction.update(
                            reference, mapOf(
                                RADIUS_KEY to serverRadius - minusValue,
                                TIMESTAMP_KEY to System.currentTimeMillis().asTimeStamp()
                            )
                        )
                    }
                }.await()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun checkReEntryOfGame(gameCode: String, token: String): Result<Boolean> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val task = fireStore.collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)
                    .get()

                Tasks.await(task)

                task.result.data.isNullOrEmpty()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }

    override suspend fun sendOutUser(gameCode: String, token: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val reference = fireStore.collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)

                fireStore.runTransaction {
                    it.update(reference, mapOf(LOST_KEY to true, HP_KEY to 0))
                }.await()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun setUserInitialHpData(gameCode: String, token: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                fireStore.collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)
                    .set(
                        AddedUserHpDocument(
                            userId = token,
                            updatedAt = System.currentTimeMillis().asTimeStamp()
                        )
                    ).await()
            }
            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun decreaseUserHp(gameCode: String, token: String, newHp: Int): Result<Int> =

        suspendCancellableCoroutine { cancellableContinuation ->
            val reference = fireStore
                .collection(PROMISE_COLLECTION_NAME)
                .document(gameCode)
                .collection(GAME_INFO_COLLECTION_NAME)
                .document(token)

            fireStore.runTransaction { transaction ->
                val snapShot = transaction.get(reference)

                transaction.update(reference, mapOf(HP_KEY to newHp))
            }.addOnSuccessListener {
                cancellableContinuation.resume(Result.success(newHp))
            }.addOnFailureListener {
                cancellableContinuation.resume(Result.failure(it))
            }
        }

    override suspend fun getUserHpAndListen(
        gameCode: String,
        token: String
    ): Flow<Result<AddedUserHpModel>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                if (value == null) {
                    return@addSnapshotListener
                }

                runCatching {
                    val result = value.toObject(AddedUserHpDocument::class.java)
                    result?.let {
                        trySend(Result.success(it.asDomain()))
                    } ?: throw UNMATCHED_STATE_EXCEPTION
                }.onFailure {
                    trySend(Result.failure(it))
                }
            }
            awaitClose { subscription?.remove() }
        }

    override suspend fun getUserHpList(gameCode: String): Result<List<AddedUserHpModel>> =
        withContext(scope.coroutineContext) {
            runCatching {
                fireStore.collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .get()
                    .await()
                    .map {
                        it.toObject(AddedUserHpDocument::class.java)
                            .asDomain()
                    }
            }
        }

    override suspend fun getUserInfoList(gameCode: String): Result<List<UserModel>> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                val task = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .get()
                Tasks.await(task)
                val res = task.result
                    .toObject(PromiseDocument::class.java)
                    ?.asDomain()
                    ?: throw UNMATCHED_STATE_EXCEPTION
                res.data.users
            }

            when (val exception = result.exceptionOrNull()) {
                null -> result
                else -> Result.failure(exception)
            }
        }


    override suspend fun setPlayerArrived(gameCode: String, token: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)
                    .update(USER_ARRIVED_KEY, true).await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun getPlayerArrived(gameCode: String, token: String): Flow<Result<Boolean>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .document(token)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                value ?: return@addSnapshotListener

                val result = runCatching {
                    value.getBoolean(USER_ARRIVED_KEY) ?: throw UNMATCHED_STATE_EXCEPTION
                }
                trySend(result)
            }
            awaitClose { subscription?.remove() }
        }


    override suspend fun getGameRealtimeRanking(gameCode: String): Flow<Result<List<AddedUserHpModel>>> =
        callbackFlow {
            var documentReference: Query? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(GAME_INFO_COLLECTION_NAME)
                    .orderBy(HP_KEY)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                if (value == null) {
                    return@addSnapshotListener
                }

                val result = runCatching {
                    val changedResult =
                        value.documents.map { it.toObject(AddedUserHpDocument::class.java) }
                    changedResult.map {
                        it?.asDomain() ?: throw UNMATCHED_STATE_EXCEPTION
                    }
                }
                trySend(result)
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun setIsFinishedPromise(gameCode: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .update(FINISHED_PROMISE_KEY, true)
                    .await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun getIsFinishedPromise(gameCode: String): Flow<Result<Boolean>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                value ?: return@addSnapshotListener

                val result = runCatching {
                    value.getBoolean(FINISHED_PROMISE_KEY) ?: throw UNMATCHED_STATE_EXCEPTION
                }
                trySend(result)
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun setIsStartedGame(gameCode: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .update(STARTED_PROMISE_KEY, true)
                    .await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun getIsStartedGame(gameCode: String): Flow<Result<Boolean>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = documentReference?.addSnapshotListener { value, _ ->
                value ?: return@addSnapshotListener

                val result = runCatching {
                    value.getBoolean(STARTED_PROMISE_KEY) ?: throw UNMATCHED_STATE_EXCEPTION
                }
                trySend(result)
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun setUserReady(gameCode: String, token: String): Result<Unit> =
        withContext(scope.coroutineContext) {
            val result = runCatching {
                fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(USER_READY_COLLECTION_NAME)
                    .document(token)
                    .set(READY_DATA)
                    .await()
            }

            when (val exception = result.exceptionOrNull()) {
                null -> Result.success(Unit)
                else -> Result.failure(exception)
            }
        }

    override suspend fun getIsReadyUser(gameCode: String, token: String): Flow<Result<Boolean>> =
        callbackFlow {
            var documentReference: DocumentReference? = null

            runCatching {
                documentReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(USER_READY_COLLECTION_NAME)
                    .document(token)
            }.onFailure {
                trySend(Result.failure(it))
            }
            val subscription = documentReference?.addSnapshotListener { value, _ ->
                value ?: return@addSnapshotListener

                val result = runCatching {
                    value.exists()
                }
                trySend(result)
            }

            awaitClose { subscription?.remove() }
        }

    override suspend fun getReadyUsers(gameCode: String): Flow<Result<List<String>>> =
        callbackFlow {
            var collectionReference: CollectionReference? = null

            runCatching {
                collectionReference = fireStore
                    .collection(PROMISE_COLLECTION_NAME)
                    .document(gameCode)
                    .collection(USER_READY_COLLECTION_NAME)
            }.onFailure {
                trySend(Result.failure(it))
            }

            val subscription = collectionReference?.addSnapshotListener { value, _ ->
                value ?: return@addSnapshotListener
                val result = runCatching {
                    value.documents.map { it.id }
                }
                trySend(result)
            }
            awaitClose { subscription?.remove() }
        }

    override suspend fun getPromisesByCodes(codes: List<String>): Flow<List<PromiseHistoryModel>?> =
        callbackFlow {
            if (codes.isEmpty()) throw UNMATCHED_STATE_EXCEPTION

            var promisesCollectionReference: CollectionReference? = null

            try {
                promisesCollectionReference = fireStore.collection(PROMISE_COLLECTION_NAME)
            } catch (e: Throwable) {
                close(e)
            }

            val subscription =
                promisesCollectionReference?.addSnapshotListener { promiseDocuments, _ ->
                    promiseDocuments?.documents?.filter { it.id in codes }
                        ?.forEach { document ->
                            document.reference.collection(MAGNETIC_COLLECTION_NAME)
                                .addSnapshotListener { _, _ ->
                                    launch {
                                        val data = promiseDocuments.documents
                                            .filter { code ->
                                                code.id in codes
                                            }.map { promiseDocument ->
                                                val promise =
                                                    promiseDocument.toObject(PromiseDocument::class.java)
                                                        ?.asDomain()
                                                        ?: throw UNMATCHED_STATE_EXCEPTION

                                                val now = OffsetDateTime.now()

                                                if (now.isBefore(promise.data.gameDateTime)) {
                                                    PromiseHistoryModel(promise = promise)
                                                } else {
                                                    val fetchMagneticDeferred = async {
                                                        getMagneticInfoByCode(promise.code).getOrNull()
                                                    }
                                                    val fetchUsersDeferred = async {
                                                        getUserHpList(promise.code).getOrNull()
                                                    }

                                                    PromiseHistoryModel(
                                                        promise = promise,
                                                        magnetic = fetchMagneticDeferred.await(),
                                                        users = fetchUsersDeferred.await()
                                                    )
                                                }
                                            }

                                        trySend(data)
                                    }
                                }
                        }
                }

            awaitClose { subscription?.remove() }
        }

    private fun isFirstAccess(prevTime: Timestamp): Boolean =
        System.currentTimeMillis() - prevTime.asMillis() >= 1000 * (MAGNETIC_FIELD_UPDATE_TERM_SECOND - 1)

    companion object {
        private const val PROMISE_COLLECTION_NAME = "Promises"
        private const val LOCATION_COLLECTION_NAME = "UserLocation"
        private const val MAGNETIC_COLLECTION_NAME = "Magnetic"
        private const val HP_COLLECTION_NAME = "Hp"
        private const val GAME_INFO_COLLECTION_NAME = "GameInfo"
        private const val USER_READY_COLLECTION_NAME = "UserReady"
        private const val HP_KEY = "hp"
        private const val RADIUS_KEY = "radius"
        private const val INITIAL_RADIUS_KEY = "initialRadius"
        private const val LOST_KEY = "lost"
        private const val TIMESTAMP_KEY = "timeStamp"
        private const val USER_ARRIVED_KEY = "arrived"
        private const val FINISHED_PROMISE_KEY = "finished"
        private const val STARTED_PROMISE_KEY = "started"
        private const val USERS_KEY = "users"
        private val UNMATCHED_STATE_EXCEPTION = IllegalStateException("Unmatched State with Server")
        private val READY_DATA = mapOf("ready" to "READY")
    }
}