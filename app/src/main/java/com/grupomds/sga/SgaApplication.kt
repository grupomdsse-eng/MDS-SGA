package com.grupomds.sga

import android.app.Application
import com.grupomds.sga.data.SgaDatabase
import com.grupomds.sga.data.SgaRepository

class SgaApplication : Application() {
    val database by lazy { SgaDatabase.get(this) }
    val repository by lazy { SgaRepository(database) }
}
