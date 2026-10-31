package app.vitaflow.com.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1")
    suspend fun byEmail(email: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE phone = :phone LIMIT 1")
    suspend fun byPhone(phone: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE googleSub = :sub LIMIT 1")
    suspend fun byGoogleSub(sub: String): AccountEntity?
}

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE accountId = :accountId ORDER BY done ASC, createdAt DESC")
    fun observe(accountId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE accountId = :accountId")
    suspend fun allOnce(accountId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tasks WHERE dueAt IS NOT NULL")
    suspend fun allWithDue(): List<TaskEntity>
}

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insertPlan(plan: WorkoutPlanEntity): Long

    @Insert
    suspend fun insertExercise(exercise: WorkoutExerciseEntity): Long

    @Update
    suspend fun updateExercise(exercise: WorkoutExerciseEntity)

    @Delete
    suspend fun deleteExercise(exercise: WorkoutExerciseEntity)

    @Query("DELETE FROM workout_exercises WHERE planId = :planId")
    suspend fun deleteExercisesOfPlan(planId: Long)

    @Query("DELETE FROM workout_plans WHERE id = :planId")
    suspend fun deletePlan(planId: Long)

    @Query("SELECT * FROM workout_plans WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observePlans(accountId: Long): Flow<List<WorkoutPlanEntity>>

    @Query("SELECT * FROM workout_plans WHERE accountId = :accountId ORDER BY createdAt DESC")
    suspend fun plansOnce(accountId: Long): List<WorkoutPlanEntity>

    @Query(
        "SELECT * FROM workout_exercises WHERE planId IN " +
            "(SELECT id FROM workout_plans WHERE accountId = :accountId) ORDER BY planId, orderIndex"
    )
    fun observeAllExercises(accountId: Long): Flow<List<WorkoutExerciseEntity>>

    @Query(
        "SELECT * FROM workout_exercises WHERE planId IN " +
            "(SELECT id FROM workout_plans WHERE accountId = :accountId) ORDER BY planId, orderIndex"
    )
    suspend fun allExercisesOnce(accountId: Long): List<WorkoutExerciseEntity>

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM workout_exercises WHERE planId = :planId")
    suspend fun maxOrder(planId: Long): Int

    @Query("SELECT * FROM workout_exercises WHERE id = :id")
    suspend fun getExercise(id: Long): WorkoutExerciseEntity?

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteExerciseById(id: Long)

    @Query("SELECT * FROM workout_plans WHERE id = :id")
    suspend fun getPlan(id: Long): WorkoutPlanEntity?

    @Query("SELECT * FROM workout_exercises WHERE planId = :planId ORDER BY orderIndex ASC")
    suspend fun exercisesOfPlan(planId: Long): List<WorkoutExerciseEntity>
}

@Dao
interface MealDao {
    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Delete
    suspend fun delete(meal: MealEntity)

    @Query("SELECT * FROM meals WHERE accountId = :accountId ORDER BY loggedAt DESC")
    fun observe(accountId: Long): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE accountId = :accountId")
    suspend fun allOnce(accountId: Long): List<MealEntity>

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM meals WHERE accountId = :accountId AND planned = 1")
    suspend fun deletePlanned(accountId: Long)

    @Query("SELECT COUNT(*) FROM meals WHERE accountId = :accountId AND planned = 1")
    suspend fun plannedCount(accountId: Long): Int
}

@Dao
interface WaterDao {
    @Insert
    suspend fun insert(log: WaterLogEntity): Long

    @Query("SELECT * FROM water_logs WHERE accountId = :accountId ORDER BY loggedAt DESC")
    fun observe(accountId: Long): Flow<List<WaterLogEntity>>

    @Query("SELECT * FROM water_logs WHERE accountId = :accountId")
    suspend fun allOnce(accountId: Long): List<WaterLogEntity>

    @Query("DELETE FROM water_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChangeDao {
    @Insert
    suspend fun insert(record: ChangeRecordEntity): Long

    @Query("SELECT * FROM change_records WHERE accountId = :accountId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(accountId: Long, limit: Int): Flow<List<ChangeRecordEntity>>

    @Query("SELECT * FROM change_records WHERE id IN (:ids) ORDER BY id ASC")
    suspend fun getByIds(ids: List<Long>): List<ChangeRecordEntity>

    @Query("UPDATE change_records SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("SELECT * FROM change_records WHERE accountId = :accountId AND undone = 0 AND action != 'UNDO' ORDER BY id DESC LIMIT 1")
    suspend fun lastActive(accountId: Long): ChangeRecordEntity?
}

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE conversationId = :cid ORDER BY id ASC")
    fun observe(cid: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :cid ORDER BY id ASC")
    suspend fun allOnce(cid: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :cid ORDER BY id DESC LIMIT :limit")
    suspend fun recent(cid: Long, limit: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE conversationId = :cid")
    suspend fun deleteConversation(cid: Long)

    @Query("SELECT * FROM chat_messages WHERE accountId = :accountId AND attachments != ''")
    suspend fun withAttachments(accountId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE accountId = :accountId")
    suspend fun clear(accountId: Long)
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE accountId = :accountId ORDER BY updatedAt DESC")
    fun observe(accountId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE accountId = :accountId ORDER BY updatedAt DESC")
    suspend fun allOnce(accountId: Long): List<ConversationEntity>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations WHERE accountId = :accountId")
    suspend fun clear(accountId: Long)
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(f: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_facts WHERE accountId = :accountId ORDER BY id DESC")
    fun observe(accountId: Long): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts WHERE accountId = :accountId ORDER BY id DESC")
    suspend fun allOnce(accountId: Long): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memory_facts WHERE accountId = :accountId")
    suspend fun clear(accountId: Long)
}

@Dao
interface ProfileDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles WHERE accountId = :accountId")
    fun observe(accountId: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE accountId = :accountId")
    suspend fun get(accountId: Long): ProfileEntity?
}

@Dao
interface WeightDao {
    @Insert
    suspend fun insert(log: WeightLogEntity): Long

    @Query("SELECT * FROM weight_logs WHERE accountId = :accountId ORDER BY loggedAt ASC")
    fun observe(accountId: Long): Flow<List<WeightLogEntity>>

    @Query("DELETE FROM weight_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Update
    suspend fun updateSession(session: WorkoutSessionEntity)

    @Insert
    suspend fun insertSet(set: SetLogEntity): Long

    @Query("SELECT * FROM workout_sessions WHERE accountId = :accountId AND finishedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinished(accountId: Long): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_sessions WHERE accountId = :accountId AND finishedAt IS NOT NULL")
    suspend fun finishedOnce(accountId: Long): List<WorkoutSessionEntity>
}
