package app.vitaflow.com.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        TaskEntity::class,
        WorkoutPlanEntity::class,
        WorkoutExerciseEntity::class,
        MealEntity::class,
        WaterLogEntity::class,
        ChangeRecordEntity::class,
        ChatMessageEntity::class,
        ProfileEntity::class,
        WeightLogEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        ConversationEntity::class,
        MemoryFactEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class VitaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun taskDao(): TaskDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun mealDao(): MealDao
    abstract fun waterDao(): WaterDao
    abstract fun changeDao(): ChangeDao
    abstract fun chatDao(): ChatDao
    abstract fun profileDao(): ProfileDao
    abstract fun weightDao(): WeightDao
    abstract fun sessionDao(): SessionDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        fun build(context: Context): VitaDatabase =
            Room.databaseBuilder(context.applicationContext, VitaDatabase::class.java, "vitaflow.db").build()
    }
}
