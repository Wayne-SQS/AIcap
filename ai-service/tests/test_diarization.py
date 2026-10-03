import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from pydantic import ValidationError
from meeting_agent.api import create_app
from meeting_agent.audio_tool import PreparedAudio, AudioReadError
from meeting_agent.diarization import DiarizationPreview, LocalDiarizer
from meeting_agent.transcription import TranscriptionError, _SLOT
from meeting_agent.speech_runtime import SPEECH_JOB_TIMEOUT_SECONDS


class DiarizationTests(unittest.TestCase):
    def setUp(self):
        self.meta=PreparedAudio(meeting_id='m1',audio_id='a1',sha256=hashlib.sha256(b'a').hexdigest(),byte_size=1,reported_duration_ms=None)
        self.data=dict(duration_ms=2000,speaker_count=2,status='draft',turns=[
            dict(start_ms=0,end_ms=1200,speaker_id='SPK1'),dict(start_ms=900,end_ms=1900,speaker_id='SPK2')])
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); model=self.root/'model'; model.mkdir()
        for name in ('segmentation.onnx','embedding.onnx'): (model/name).write_bytes(b'model')
        self.env=patch.dict(os.environ,{'AICAP_DIARIZATION_MODEL_DIR':str(model),'AICAP_LLM_API_KEY':'private-key'}); self.env.start(); self.addCleanup(self.env.stop)
    def worker(self,args,**kwargs):
        self.assertEqual(SPEECH_JOB_TIMEOUT_SECONDS,kwargs['timeout']); self.assertEqual(b'a',Path(args[4]).read_bytes())
        self.assertNotIn('AICAP_LLM_API_KEY',kwargs['env'])
        Path(args[5]).write_text(json.dumps(self.data),encoding='utf-8'); return Mock(returncode=0)
    def invoke(self,hint=2):
        with patch('meeting_agent.diarization.ROOT',self.root),patch('meeting_agent.diarization.subprocess.run',side_effect=self.worker):
            return LocalDiarizer().run(self.meta,b'a',hint)
    def test_validates_overlap_and_anonymous_sequence(self):
        result=self.invoke(); self.assertEqual(2,result.speaker_count); self.assertEqual('anonymous_only',result.identity_status)
        self.assertEqual([],list((self.root/'.stt-work').iterdir()))
        for field,value in [('start_ms',-1),('end_ms',3000),('speaker_id','Alice')]:
            data=json.loads(json.dumps(self.data)); data['turns'][0][field]=value
            with self.assertRaises(ValidationError): DiarizationPreview(audio=self.meta,requested_num_speakers=2,**data)
        self.data['turns'][1]['speaker_id']='SPK3'
        with self.assertRaises(TranscriptionError): self.invoke()
    def test_missing_model_busy_timeout_and_worker_error(self):
        with patch.dict(os.environ,{'AICAP_DIARIZATION_MODEL_DIR':str(self.root/'absent')}),self.assertRaises(TranscriptionError) as error:
            LocalDiarizer().run(self.meta,b'a')
        self.assertEqual('diarization_model_not_installed',error.exception.code)
        _SLOT.acquire()
        try:
            with self.assertRaises(TranscriptionError) as error: self.invoke()
            self.assertEqual('stt_busy',error.exception.code)
        finally: _SLOT.release()
        with patch('meeting_agent.diarization.ROOT',self.root),patch('meeting_agent.diarization.subprocess.run',side_effect=subprocess.TimeoutExpired('worker',SPEECH_JOB_TIMEOUT_SECONDS)):
            with self.assertRaises(TranscriptionError) as error: LocalDiarizer().run(self.meta,b'a')
            self.assertEqual(504,error.exception.status)
        self.assertEqual([],list((self.root/'.stt-work').iterdir()))
        self.assertEqual('draft',self.invoke().status)
        self.data={'error':'private-detail'}
        with self.assertRaises(TranscriptionError) as error: self.invoke()
        self.assertEqual('diarization_failed',error.exception.code)
    def test_api_auth_validation_and_read_before_inference(self):
        reader=Mock(); reader.read.return_value=(self.meta,b'a')
        engine=Mock(); engine.run.return_value=DiarizationPreview(audio=self.meta,requested_num_speakers=2,**self.data)
        with patch('meeting_agent.api.AudioReadTool',return_value=reader),patch('meeting_agent.api.LocalDiarizer',return_value=engine),TestClient(create_app()) as client:
            path='/api/meetings/m1/diarization/run'; headers={'Authorization':'Bearer token'}
            self.assertEqual(401,client.post(path,json={'audio_id':'a1'}).status_code)
            for value in (0,9,2.5,True): self.assertEqual(422,client.post(path,headers=headers,json={'audio_id':'a1','num_speakers':value}).status_code)
            engine.run.assert_not_called()
            self.assertEqual(200,client.post(path,headers=headers,json={'audio_id':'a1','num_speakers':2}).status_code)
            engine.run.assert_called_once_with(self.meta,b'a',2)
            reader.read.side_effect=AudioReadError('transcription_forbidden',403)
            self.assertEqual(403,client.post(path,headers=headers,json={'audio_id':'a1'}).status_code)
            self.assertEqual(1,engine.run.call_count)

if __name__=='__main__': unittest.main()
