package com.example.sensorlogger.util

import com.example.sensorlogger.model.Quaternion
import timber.log.Timber

data class EarthFrameAcceleration(
    val accLongitudinal: Float?,
    val accLateral: Float?,
    val accVertical: Float?
)

object ImuMath {
    
    /**
     * Converte aceleração do frame do sensor (tablet) para o frame da Terra (world frame)
     * usando o quaternion de orientação atual.
     * 
     * O quaternion representa a rotação do sensor em relação à Terra.
     * A aceleração linear já está sem gravidade (removida pelo Android).
     * 
     * @param quaternion Orientação atual do dispositivo (w, x, y, z)
     * @param linAx Aceleração linear X no frame do sensor
     * @param linAy Aceleração linear Y no frame do sensor
     * @param linAz Aceleração linear Z no frame do sensor
     * @return Acelerações no frame da Terra (lateral=East/West, longitudinal=North/South, vertical=Up)
     */
    fun computeEarthFrameAcceleration(
        quaternion: Quaternion?,
        linAx: Float,
        linAy: Float,
        linAz: Float
    ): EarthFrameAcceleration {
        // Validações
        if (quaternion == null) {
            return EarthFrameAcceleration(null, null, null)
        }
        
        if (!linAx.isFinite() || !linAy.isFinite() || !linAz.isFinite()) {
            return EarthFrameAcceleration(null, null, null)
        }
        
        if (!quaternion.w.isFinite() || !quaternion.x.isFinite() || 
            !quaternion.y.isFinite() || !quaternion.z.isFinite()) {
            return EarthFrameAcceleration(null, null, null)
        }
        
        return try {
            val (qw, qx, qy, qz) = quaternion
            
            // Construir matriz de rotação 3x3 a partir do quaternion
            // Fórmula padrão: R = quaternion_to_rotation_matrix(q)
            val r00 = 1f - 2f * (qy * qy + qz * qz)
            val r01 = 2f * (qx * qy - qz * qw)
            val r02 = 2f * (qx * qz + qy * qw)
            
            val r10 = 2f * (qx * qy + qz * qw)
            val r11 = 1f - 2f * (qx * qx + qz * qz)
            val r12 = 2f * (qy * qz - qx * qw)
            
            val r20 = 2f * (qx * qz - qy * qw)
            val r21 = 2f * (qy * qz + qx * qw)
            val r22 = 1f - 2f * (qx * qx + qy * qy)
            
            // Multiplicar matriz de rotação pelo vetor de aceleração
            // worldAcc = R * sensorAcc
            val worldX = r00 * linAx + r01 * linAy + r02 * linAz
            val worldY = r10 * linAx + r11 * linAy + r12 * linAz
            val worldZ = r20 * linAx + r21 * linAy + r22 * linAz
            
            // Validar resultados
            if (!worldX.isFinite() || !worldY.isFinite() || !worldZ.isFinite()) {
                return EarthFrameAcceleration(null, null, null)
            }
            
            // Mapear para convenções do veículo:
            // - lateral (X) = East/West = movimento lateral do veículo
            // - longitudinal (Y) = North/South = movimento para frente/trás
            // - vertical (Z) = Up = movimento vertical (positivo para cima)
            val result = EarthFrameAcceleration(
                accLongitudinal = worldY,
                accLateral = worldX,
                accVertical = worldZ
            )
            
            Timber.d("IMU_FRAME longitudinal=%.3f, lateral=%.3f, vertical=%.3f", 
                result.accLongitudinal, result.accLateral, result.accVertical)
            
            result
        } catch (e: Exception) {
            Timber.w(e, "Failed to compute earth frame acceleration")
            EarthFrameAcceleration(null, null, null)
        }
    }
    
    /**
     * Versão alternativa que recebe componentes do quaternion separadamente
     */
    fun computeEarthFrameAcceleration(
        qw: Float,
        qx: Float,
        qy: Float,
        qz: Float,
        linAx: Float,
        linAy: Float,
        linAz: Float
    ): EarthFrameAcceleration {
        return computeEarthFrameAcceleration(
            Quaternion(qw, qx, qy, qz),
            linAx, linAy, linAz
        )
    }
}
